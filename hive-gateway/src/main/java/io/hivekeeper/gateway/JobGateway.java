package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.gateway.JobService.JobRow;
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

    private static final long DEADLINE_MS = 120_000;

    private final JobService jobs;
    private final JsonCodec codec = new JsonCodec();
    private final Map<String, AgentConn> connected = new ConcurrentHashMap<>();

    public JobGateway(JobService jobs) {
        this.jobs = jobs;
    }

    /** Submit a durable job. Persisted first; dispatched if the agent is connected, else it waits for
     *  the agent to reconnect (then it is redelivered). */
    public String submit(String tenantId, String agentId, String type, Command command) {
        String idempotencyKey = UUID.randomUUID().toString();
        String jobId = jobs.create(tenantId, agentId, idempotencyKey, type, codec.toJson(command));

        AgentConn conn = connected.get(key(tenantId, agentId));
        if (conn != null) {
            send(conn, jobId, idempotencyKey, command);
            log.info("dispatched job {} to agent {}", jobId, agentId);
        } else {
            log.info("agent {} offline; job {} queued for redelivery", agentId, jobId);
        }
        return jobId;
    }

    public Optional<JobRow> get(String tenantId, String jobId) {
        return jobs.get(tenantId, jobId);
    }

    public void onAgentConnected(String tenantId, String agentId, FrameChannel channel) {
        connected.put(key(tenantId, agentId), new AgentConn(tenantId, agentId, channel));
        int redelivered = 0;
        for (JobRow row : jobs.pendingFor(tenantId, agentId)) {
            Command command = codec.fromJson(row.commandJson(), Command.class);
            send(new AgentConn(tenantId, agentId, channel), row.jobId(), row.idempotencyKey(), command);
            redelivered++;
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
            case Frame.JobResult r -> jobs.complete(tenantId, r.jobId(), true, codec.toJson(r.result()), null);
            case Frame.JobFailed f -> jobs.complete(tenantId, f.jobId(), false, null, f.error() + ": " + f.detail());
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
