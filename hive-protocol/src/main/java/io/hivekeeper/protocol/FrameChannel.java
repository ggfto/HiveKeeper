package io.hivekeeper.protocol;

import java.util.function.Consumer;

/**
 * A bidirectional, ordered channel for {@link Frame}s. Abstracts away the medium (WebSocket today, an
 * in-memory pair for tests/embedded) so {@link RemoteEngine} and {@link AgentRuntime} never depend on a
 * transport. Implementations must deliver inbound frames to the registered handler on a thread other
 * than the caller of {@link #send} (so a blocking sender does not deadlock the receiver).
 */
public interface FrameChannel extends AutoCloseable {

    void send(Frame frame);

    /** Registers the handler for inbound frames. The latest registration wins. */
    void onFrame(Consumer<Frame> handler);

    @Override
    void close();
}
