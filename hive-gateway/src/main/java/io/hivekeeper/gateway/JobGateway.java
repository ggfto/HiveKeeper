package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.gateway.JobService.JobRow;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.crypto.Secrets;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The durable-job control plane: persists a job, dispatches it to a connected agent, and — crucially —
 * <strong>redelivers unfinished jobs when an agent reconnects</strong>, so a transient disconnect no
 * longer loses work. The agent de-dupes by idempotency key, giving at-least-once delivery with
 * idempotent execution. Only present under the {@code postgres} profile.
 */
@Component
@Profile("postgres")
@Slf4j
public class JobGateway {

    private record AgentConn(String tenantId, String agentId, FrameChannel channel) {
    }

    /** A safe, operator-facing projection of a job: no {@code command_json} (it holds the raw secret), and
     *  the result is decrypted then secret-masked. */
    public record JobView(String jobId, String agentId, String type, String status, String error,
                          String resultJson) {
    }

    private static final long DEADLINE_MS = 120_000;

    private final JobService jobs;
    private final SecretCipher cipher;
    private final FleetService fleet;
    private final JsonCodec codec = new JsonCodec();
    private final Map<String, AgentConn> connected = new ConcurrentHashMap<>();

    public JobGateway(JobService jobs, SecretCipher cipher, FleetService fleet) {
        this.jobs = jobs;
        this.cipher = cipher;
        this.fleet = fleet;
    }

    /** Submit a durable job. Persisted first; dispatched if the agent is connected, else it waits for
     *  the agent to reconnect (then it is redelivered). */
    public String submit(String tenantId, String agentId, String type, Command command) {
        String idempotencyKey = UUID.randomUUID().toString();
        // The command may carry a secret (an SSID passphrase, a hive password). Encrypt before it touches
        // the database so the secret is never written at rest in the clear.
        String jobId = jobs.create(tenantId, agentId, idempotencyKey, type, cipher.encrypt(codec.toJson(command)));

        AgentConn conn = connected.get(key(tenantId, agentId));
        if (conn != null) {
            send(conn, jobId, idempotencyKey, command);
            log.info("dispatched job {} to agent {}", jobId, agentId);
        } else {
            log.info("agent {} offline; job {} queued for redelivery", agentId, jobId);
        }
        return jobId;
    }

    /** Operator-facing job lookup: decrypts the result and masks secrets, and never returns the raw
     *  command blob. */
    public Optional<JobView> view(String tenantId, String jobId) {
        return jobs.get(tenantId, jobId).map(row -> new JobView(
                row.jobId(), row.agentId(), row.type(), row.status(), Secrets.redact(row.error()),
                redactedResult(row.resultJson())));
    }

    /** Decrypts a stored result and masks secrets at the FIELD level — parsing back to a {@link Result} so
     *  redaction operates on the CLI lines, not on the JSON text (a line-level regex over a JSON blob would
     *  both corrupt the structure and mask only by accident). A decrypt failure (e.g. a row written under a
     *  rotated key) degrades to a placeholder instead of 500-ing the status endpoint. */
    private String redactedResult(String storedResultJson) {
        if (storedResultJson == null) {
            return null;
        }
        try {
            Result result = codec.fromJson(cipher.decrypt(storedResultJson), Result.class);
            return codec.toJson(Secrets.redactResult(result));
        } catch (RuntimeException e) {
            log.warn("could not decrypt/redact a job result: {}", e.getMessage());
            return "{\"unavailable\":\"result could not be decrypted with the current key\"}";
        }
    }

    public void onAgentConnected(String tenantId, String agentId, FrameChannel channel) {
        connected.put(key(tenantId, agentId), new AgentConn(tenantId, agentId, channel));
        // If this agent is now the serving agent among its reachable peers, adopt any unfinished jobs its
        // offline peers were holding — the case where the serving agent was already down when this one came
        // up. The redelivery below then sends them along with this agent's own pending jobs, in one pass.
        claimOrphanedJobsIfServing(tenantId, agentId);
        int redelivered = 0;
        for (JobRow row : jobs.pendingFor(tenantId, agentId)) {
            try {
                Command command = codec.fromJson(cipher.decrypt(row.commandJson()), Command.class);
                send(new AgentConn(tenantId, agentId, channel), row.jobId(), row.idempotencyKey(), command);
                redelivered++;
            } catch (RuntimeException e) {
                // An undecryptable row (wrong/rotated key, corruption) must not abort redelivery of the
                // OTHER pending jobs, nor escape into the WebSocket connect callback. Skip it — the job
                // stays PENDING and is recoverable if the key is fixed — and log the jobId only, never the
                // ciphertext or the secret.
                log.error("skipping redelivery of job {} for agent {}: {}", row.jobId(), agentId, e.getMessage());
            }
        }
        if (redelivered > 0) {
            log.info("redelivered {} unfinished job(s) to agent {}", redelivered, agentId);
        }
    }

    public void onAgentDisconnected(String tenantId, String agentId) {
        connected.remove(key(tenantId, agentId));
        // The serving agent just dropped. If a reachable peer (one that shares a device with it) is up, move
        // the dropped agent's unfinished jobs to it and dispatch them now — so a config or backup queued for
        // it is not stranded until it returns. Nothing to do when no peer shares its devices (the common case).
        currentServingPeer(tenantId, agentId).filter(newServer -> !newServer.equals(agentId)).ifPresent(newServer -> {
            List<JobRow> moved = jobs.reassign(tenantId, agentId, newServer);
            AgentConn conn = connected.get(key(tenantId, newServer));
            if (conn == null) {
                return;   // the new server vanished between the check and here; its own reconnect will claim
            }
            int sent = 0;
            for (JobRow row : moved) {
                if (redeliver(conn, row)) {
                    sent++;
                }
            }
            if (sent > 0) {
                log.info("failed over {} job(s) from {} to peer {}", sent, agentId, newServer);
            }
        });
    }

    /** When {@code agentId} is the current serving agent among its reachable peers, claim any of those peers'
     *  unfinished jobs that are stranded on an offline peer. */
    private void claimOrphanedJobsIfServing(String tenantId, String agentId) {
        List<String> peers = fleet.reachablePeers(tenantId, agentId);
        if (peers.isEmpty() || currentServingPeer(tenantId, agentId).filter(agentId::equals).isEmpty()) {
            return;
        }
        for (String peer : peers) {
            if (!connected.containsKey(key(tenantId, peer))) {
                List<JobRow> moved = jobs.reassign(tenantId, peer, agentId);
                if (!moved.isEmpty()) {
                    log.info("serving agent {} claimed {} orphaned job(s) from offline {}", agentId, moved.size(), peer);
                }
            }
        }
    }

    /**
     * The agent that should serve now among {@code agentId} and its reachable peers: the first (by id) that is
     * currently connected. Computed over this gateway's own connected map (the same one it dispatches through)
     * so the choice and the ability to send it are always consistent.
     */
    private Optional<String> currentServingPeer(String tenantId, String agentId) {
        SortedSet<String> candidates = new TreeSet<>(fleet.reachablePeers(tenantId, agentId));
        candidates.add(agentId);   // the agent itself is a candidate to serve its own devices
        return candidates.stream()
                .filter(a -> connected.containsKey(key(tenantId, a)))
                .findFirst();
    }

    /** Sends one stored job to a connected agent; returns false (and logs) if its command cannot be decrypted. */
    private boolean redeliver(AgentConn conn, JobRow row) {
        try {
            Command command = codec.fromJson(cipher.decrypt(row.commandJson()), Command.class);
            send(conn, row.jobId(), row.idempotencyKey(), command);
            return true;
        } catch (RuntimeException e) {
            log.error("skipping redelivery of job {} to {}: {}", row.jobId(), conn.agentId(), e.getMessage());
            return false;
        }
    }

    /** Route a terminal frame from an agent to the job record. Unknown job ids (e.g. synchronous jobs
     *  handled by RemoteEngine) update zero rows and are ignored. */
    public void onFrame(String tenantId, Frame frame) {
        switch (frame) {
            // The result can echo applied CLI lines (with secrets), so encrypt it at rest like the command.
            case Frame.JobResult r -> jobs.complete(tenantId, r.jobId(), true, cipher.encrypt(codec.toJson(r.result())), null);
            // A failure detail could quote a command line; mask before it lands in the error column.
            case Frame.JobFailed f -> jobs.complete(tenantId, f.jobId(), false, null,
                    Secrets.redact(f.error() + ": " + f.detail()));
            default -> { /* events / control frames: not terminal */ }
        }
    }

    private void send(AgentConn conn, String jobId, String idempotencyKey, Command command) {
        conn.channel().send(new Frame.Job(jobId, idempotencyKey, System.currentTimeMillis() + DEADLINE_MS, command));
        jobs.markDispatched(conn.tenantId(), jobId);
    }

    private static String key(String tenantId, String agentId) {
        return tenantId + "/" + agentId;
    }
}
