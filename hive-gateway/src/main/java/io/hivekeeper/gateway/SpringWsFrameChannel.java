package io.hivekeeper.gateway;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.function.Consumer;

/** Adapts one Spring {@link WebSocketSession} (an agent connection) to a {@link FrameChannel} so a
 *  {@link RemoteEngine} can drive that agent. One channel + engine per connected agent. */
@Slf4j
final class SpringWsFrameChannel implements FrameChannel {

    private final WebSocketSession session;
    private final JsonCodec codec;
    private volatile Consumer<Frame> handler = frame -> { };
    private volatile RemoteEngine engine;

    SpringWsFrameChannel(WebSocketSession session, JsonCodec codec) {
        this.session = session;
        this.codec = codec;
    }

    @Override
    public void send(Frame frame) {
        try {
            // Spring WebSocket sessions are not safe for concurrent sends.
            synchronized (session) {
                session.sendMessage(new TextMessage(codec.toJson(frame)));
            }
        } catch (IOException e) {
            log.debug("send to agent failed: {}", e.getMessage());
        }
    }

    @Override
    public void onFrame(Consumer<Frame> handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Called by the WebSocket handler when a frame arrives from the agent. */
    void deliver(Frame frame) {
        handler.accept(frame);
    }

    void attachEngine(RemoteEngine engine) {
        this.engine = engine;
    }

    RemoteEngine engine() {
        return engine;
    }
}
