package io.hivekeeper.protocol;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link AgentRuntime#close()} drains rather than kills — the property an auto-update restart
 * depends on. A job that is mid-flight when shutdown begins must finish and get its terminal frame sent, so
 * the gateway marks it done and never redelivers it to the replacement container.
 */
class AgentRuntimeDrainTest {

    /** A channel that records what the agent sends and can inject a frame into the agent. */
    private static final class RecordingChannel implements FrameChannel {
        final List<Frame> sent = new CopyOnWriteArrayList<>();
        private java.util.function.Consumer<Frame> handler;

        @Override
        public void send(Frame frame) {
            sent.add(frame);
        }

        @Override
        public void onFrame(java.util.function.Consumer<Frame> handler) {
            this.handler = handler;
        }

        void deliver(Frame frame) {
            handler.accept(frame);
        }

        @Override
        public void close() {
        }
    }

    private static Frame.Job job() {
        return new Frame.Job(UUID.randomUUID().toString(), "idem-1", System.currentTimeMillis() + 60_000,
                Command.Inventory.of(DeviceRef.ssh("10.0.0.1", 22)));
    }

    @Test
    void letsAnInFlightJobFinishBeforeCloseReturns() throws Exception {
        CountDownLatch jobStarted = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        Engine slowEngine = (command, sink) -> {
            jobStarted.countDown();
            try {
                Thread.sleep(400);   // still running when close() is called
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted — NOT drained", e);
            }
            finished.set(true);
            return new Result.Inventory(command.commandId(),
                    ((Command.Inventory) command).device().id(),
                    Device.builder().id(((Command.Inventory) command).device().id()).model("AP230").build());
        };

        RecordingChannel channel = new RecordingChannel();
        AgentRuntime agent = new AgentRuntime(slowEngine, channel, "agent-1", Duration.ofSeconds(5));
        agent.start();
        channel.deliver(job());
        assertTrue(jobStarted.await(2, TimeUnit.SECONDS), "the job should have started");

        agent.close();   // must block until the job drains

        assertTrue(finished.get(), "the job must have run to completion, not been interrupted");
        assertTrue(channel.sent.stream().anyMatch(f -> f instanceof Frame.JobResult),
                "the terminal result must have been sent before close returned");
        assertFalse(channel.sent.stream().anyMatch(f -> f instanceof Frame.JobFailed),
                "a drained job must not report as failed");
    }

    @Test
    void interruptsAJobThatOutlastsTheDrainWindow() throws Exception {
        // A drain window is a bound, not a promise to wait forever. A job that overruns it is interrupted, and
        // the log warns that the gateway will redeliver it — the honest fallback, not a hang.
        CountDownLatch started = new CountDownLatch(1);
        Engine tooSlow = (command, sink) -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result.Inventory(command.commandId(),
                    ((Command.Inventory) command).device().id(),
                    Device.builder().id(((Command.Inventory) command).device().id()).build());
        };

        RecordingChannel channel = new RecordingChannel();
        AgentRuntime agent = new AgentRuntime(tooSlow, channel, "agent-1", Duration.ofMillis(200));
        agent.start();
        channel.deliver(job());
        assertTrue(started.await(2, TimeUnit.SECONDS));

        long before = System.currentTimeMillis();
        agent.close();
        long elapsed = System.currentTimeMillis() - before;

        assertTrue(elapsed < 3_000, "close must give up near the drain window, not wait for the whole job");
    }

    @Test
    void aRedeliveredJobReturnsTheCachedResultInsteadOfRunningTwice() throws Exception {
        // The in-process dedupe that the drain protects: a job redelivered under the same idempotency key
        // returns the cached terminal rather than re-executing. Redelivery is sequential in reality (the job
        // completes, then the gateway resends it on the next reconnect), so the test drives it that way.
        int[] runs = {0};
        Engine counting = (command, sink) -> {
            runs[0]++;
            return new Result.Inventory(command.commandId(),
                    ((Command.Inventory) command).device().id(),
                    Device.builder().id(((Command.Inventory) command).device().id()).build());
        };
        RecordingChannel channel = new RecordingChannel();
        try (AgentRuntime agent = new AgentRuntime(counting, channel, "agent-1", Duration.ofSeconds(5))) {
            agent.start();
            Frame.Job j = job();

            channel.deliver(j);
            waitForResults(channel, 1);
            channel.deliver(j);   // same idempotency key — a redelivery, after the first completed
            waitForResults(channel, 2);

            assertEquals(1, runs[0], "the command must have executed once");
            assertInstanceOf(Frame.JobResult.class, channel.sent.get(channel.sent.size() - 1));
        }
    }

    private static void waitForResults(RecordingChannel channel, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (channel.sent.stream().filter(f -> f instanceof Frame.JobResult).count() >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("expected " + count + " results, saw " + channel.sent);
    }
}
