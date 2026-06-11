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
 * Accepts agent WebSocket connections that already passed {@link AgentAuthInterceptor} (so the session
 * carries a server-verified agentId + tenantId). Each connection gets its own
 * {@link SpringWsFrameChannel} + {@link RemoteEngine}, registered under its tenant. The agent's
 * {@code Hello} is informational only — authorization uses the verified identity, never the claim.
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
        String tenantId = (String) session.getAttributes().get(AgentAuthInterceptor.ATTR_TENANT_ID);
        String agentId = (String) session.getAttributes().get(AgentAuthInterceptor.ATTR_AGENT_ID);

        SpringWsFrameChannel channel = new SpringWsFrameChannel(session, codec);
        RemoteEngine engine = new RemoteEngine(channel, Duration.ofSeconds(60));
        channel.attachEngine(engine);
        channels.put(session.getId(), channel);
        registry.register(tenantId, agentId, session.getId(), engine);
        log.info("agent '{}' connected for tenant '{}'", agentId, tenantId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SpringWsFrameChannel channel = channels.get(session.getId());
        if (channel == null) {
            return;
        }
        Frame frame = codec.fromJson(message.getPayload(), Frame.class);
        if (frame instanceof Frame.Hello hello) {
            String verified = (String) session.getAttributes().get(AgentAuthInterceptor.ATTR_AGENT_ID);
            if (!hello.agentId().equals(verified)) {
                log.warn("agent claims id '{}' but verified id is '{}' — ignoring the claim",
                        hello.agentId(), verified);
            }
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
