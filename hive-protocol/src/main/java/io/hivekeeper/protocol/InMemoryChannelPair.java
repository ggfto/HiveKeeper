package io.hivekeeper.protocol;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Two linked {@link FrameChannel}s — a gateway side and an agent side — where a frame sent on one is
 * delivered to the other's handler on a dedicated thread. This is a real, useful channel (embedded
 * deployments could run both ends in one process) and the basis of the loopback test that proves
 * "remote == local is just wiring". The async hand-off is what lets {@link RemoteEngine#execute} block
 * on a result while the agent runs the job.
 */
public final class InMemoryChannelPair implements AutoCloseable {

    private final Endpoint gateway = new Endpoint("gateway");
    private final Endpoint agent = new Endpoint("agent");

    public InMemoryChannelPair() {
        gateway.peer = agent;
        agent.peer = gateway;
    }

    public FrameChannel gatewaySide() {
        return gateway;
    }

    public FrameChannel agentSide() {
        return agent;
    }

    @Override
    public void close() {
        gateway.close();
        agent.close();
    }

    private static final class Endpoint implements FrameChannel {

        private final ExecutorService delivery;
        private volatile Consumer<Frame> handler = frame -> { };
        private Endpoint peer;

        Endpoint(String name) {
            this.delivery = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "frame-" + name);
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public void send(Frame frame) {
            Endpoint target = peer;
            target.delivery.submit(() -> target.handler.accept(frame));
        }

        @Override
        public void onFrame(Consumer<Frame> handler) {
            this.handler = handler;
        }

        @Override
        public void close() {
            delivery.shutdownNow();
        }
    }
}
