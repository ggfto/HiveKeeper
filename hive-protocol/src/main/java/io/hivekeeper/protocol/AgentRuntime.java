package io.hivekeeper.protocol;

import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The on-prem agent's job loop: it receives {@link Frame.Job}s over a {@link FrameChannel}, runs them
 * through the local {@link Engine} (which holds the SSH reach and resolves credentials locally — they
 * never leave the LAN), streams each {@link io.hivekeeper.core.api.Event} back as a {@link Frame.JobEvent},
 * and sends the terminal {@link Frame.JobResult} / {@link Frame.JobFailed}. This is the whole agent
 * minus the actual WebSocket transport, which a thin {@code hive-agent} wrapper will supply.
 */
@Slf4j
public final class AgentRuntime implements AutoCloseable {

    private final Engine engine;
    private final FrameChannel channel;
    private final String agentId;
    private final ExecutorService jobs;

    public AgentRuntime(Engine engine, FrameChannel channel, String agentId) {
        this.engine = engine;
        this.channel = channel;
        this.agentId = agentId;
        this.jobs = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "agent-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Registers the job handler. Call once. */
    public void start() {
        channel.onFrame(this::onFrame);
        log.info("agent '{}' serving jobs (protocol {})", agentId, Protocol.VERSION);
    }

    /** Announces this agent to the gateway. Call on every (re)connect so the gateway re-identifies it. */
    public void announce() {
        channel.send(new Frame.Hello(agentId, Protocol.VERSION));
    }

    private void onFrame(Frame frame) {
        if (frame instanceof Frame.Job job) {
            jobs.submit(() -> runJob(job));
        }
        // Ack / Heartbeat are handled by the transport wrapper; ignored here.
    }

    private void runJob(Frame.Job job) {
        AtomicLong seq = new AtomicLong();
        EventSink sink = event -> channel.send(new Frame.JobEvent(job.jobId(), seq.incrementAndGet(), event));
        try {
            Result result = engine.execute(job.command(), sink);
            channel.send(new Frame.JobResult(job.jobId(), result));
        } catch (Exception e) {
            log.warn("job {} failed: {}", job.jobId(), e.getMessage());
            channel.send(new Frame.JobFailed(job.jobId(), e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @Override
    public void close() {
        jobs.shutdownNow();
    }
}
