package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.gateway.JobService.JobRow;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.crypto.Secrets;
import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
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
    private final JsonCodec codec = new JsonCodec();
    private final Map<String, AgentConn> connected = new ConcurrentHashMap<>();

    public JobGateway(JobService jobs, SecretCipher cipher) {
        this.jobs = jobs;
        this.cipher = cipher;
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
