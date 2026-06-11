package io.hivekeeper.gateway;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.RemoteEngine;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts outbound agent WebSocket connections. Each connection gets its own
 * {@link SpringWsFrameChannel} + {@link RemoteEngine}; when the agent sends {@link Frame.Hello}, it is
 * registered by id so the REST API can dispatch jobs to it. Inbound frames are routed to the engine.
 */
@Component
@Slf4j
class AgentWebSocketHandler extends TextWebSocketHandler {

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;
    private final Map<String, SpringWsFrameChannel> channels = new ConcurrentHashMap<>();

    AgentWebSocketHandler(AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SpringWsFrameChannel channel = new SpringWsFrameChannel(session, codec);
        RemoteEngine engine = new RemoteEngine(channel, Duration.ofSeconds(60));
        channel.attachEngine(engine);
        channels.put(session.getId(), channel);
        log.info("agent socket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SpringWsFrameChannel channel = channels.get(session.getId());
        if (channel == null) {
            return;
        }
        Frame frame = codec.fromJson(message.getPayload(), Frame.class);
        if (frame instanceof Frame.Hello hello) {
            registry.register(hello.agentId(), session.getId(), channel.engine());
            log.info("agent '{}' registered (protocol {})", hello.agentId(), hello.protocolVersion());
        }
        channel.deliver(frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        channels.remove(session.getId());
        registry.unregisterBySession(session.getId());
        log.info("agent socket closed: {} ({})", session.getId(), status);
    }
}
