package io.hivekeeper.protocol;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceId;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The gateway-side {@link Engine}: instead of running work in-process, it dispatches a {@link Frame.Job}
 * to an on-prem agent over a {@link FrameChannel} and streams the agent's {@link Frame.JobEvent}s into
 * the caller's {@link EventSink}, returning the terminal {@link Result}. It implements the exact same
 * {@code Engine} interface as {@code LocalEngine} — a caller (a cloud request handler) cannot tell
 * whether the engine is in-process or 1000 km away. This is the payoff of the v0.1 seams.
 */
@Slf4j
public final class RemoteEngine implements Engine {

    private record Pending(EventSink sink, CompletableFuture<Result> future) {
    }

    private final FrameChannel channel;
    private final Duration timeout;
    private final Map<String, Pending> inFlight = new ConcurrentHashMap<>();

    public RemoteEngine(FrameChannel channel, Duration timeout) {
        this.channel = channel;
        this.timeout = timeout;
        channel.onFrame(this::onFrame);
    }

    @Override
    public Result execute(Command command, EventSink sink) throws HiveException {
        String jobId = Protocol.newJobId();
        CompletableFuture<Result> future = new CompletableFuture<>();
        inFlight.put(jobId, new Pending(sink, future));
        try {
            channel.send(new Frame.Job(jobId, jobId, System.currentTimeMillis() + timeout.toMillis(), command));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("remote job {} failed: {}", jobId, e.getMessage());
            throw new HiveException(command.commandId(), deviceIdOf(command),
                    "remote execute failed: " + e.getMessage(), e);
        } finally {
            inFlight.remove(jobId);
        }
    }

    private static DeviceId deviceIdOf(Command command) {
        return switch (command) {
            case Command.Inventory c -> c.device().id();
            case Command.BackupConfig c -> c.device().id();
            case Command.RunRaw c -> c.device().id();
            case Command.Discover c -> DeviceId.of(c.cidr());
        };
    }

    private void onFrame(Frame frame) {
        switch (frame) {
            case Frame.JobEvent je -> {
                Pending p = inFlight.get(je.jobId());
                if (p != null) {
                    p.sink().emit(je.event());
                    channel.send(new Frame.Ack(je.jobId(), je.seq()));
                }
            }
            case Frame.JobResult jr -> {
                Pending p = inFlight.get(jr.jobId());
                if (p != null) {
                    p.future().complete(jr.result());
                }
            }
            case Frame.JobFailed jf -> {
                Pending p = inFlight.get(jf.jobId());
                if (p != null) {
                    p.future().completeExceptionally(new IllegalStateException(jf.error() + ": " + jf.detail()));
                }
            }
            default -> {
                // Hello / Resume / Ack / Heartbeat are connection-management frames; ignored in this
                // reference engine (a full gateway handles enrollment, redelivery, and liveness).
            }
        }
    }
}
