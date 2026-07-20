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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts agent WebSocket connections that already passed {@link AgentAuthInterceptor} (so the session
 * carries a server-verified agentId + tenantId). Each connection gets a {@link SpringWsFrameChannel} +
 * {@link RemoteEngine} (synchronous request/response), and — when persistence is enabled — drives the
 * durable {@link JobGateway} (redeliver on connect, complete on terminal frames).
 */
@Component
@Slf4j
class AgentWebSocketHandler extends TextWebSocketHandler {

    private final JsonCodec codec = new JsonCodec();
    private final AgentRegistry registry;
    private final Optional<JobGateway> jobGateway;
    private final Map<String, SpringWsFrameChannel> channels = new ConcurrentHashMap<>();

    private final BackupDestinationProvisioner backupDestinations;

    AgentWebSocketHandler(AgentRegistry registry, Optional<JobGateway> jobGateway,
                          BackupDestinationProvisioner backupDestinations) {
        this.registry = registry;
        this.jobGateway = jobGateway;
        this.backupDestinations = backupDestinations;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tenantId = tenantId(session);
        String agentId = agentId(session);

        SpringWsFrameChannel channel = new SpringWsFrameChannel(session, codec);
        RemoteEngine engine = new RemoteEngine(channel, Duration.ofSeconds(60));
        channel.attachEngine(engine);
        channels.put(session.getId(), channel);
        registry.register(tenantId, agentId, session.getId(), engine);
        Object pubKey = session.getAttributes().get(AgentAuthInterceptor.ATTR_AGENT_PUBKEY);
        if (pubKey instanceof java.security.PublicKey key) {
            registry.registerPublicKey(tenantId, agentId, key);
        }
        jobGateway.ifPresent(jg -> jg.onAgentConnected(tenantId, agentId, channel));
        // An agent that was offline when the destination was set picks it up here.
        backupDestinations.onAgentConnected(tenantId, agentId);
        log.info("agent '{}' connected for tenant '{}'", agentId, tenantId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SpringWsFrameChannel channel = channels.get(session.getId());
        if (channel == null) {
            return;
        }
        Frame frame = codec.fromJson(message.getPayload(), Frame.class);
        if (frame instanceof Frame.Hello hello && !hello.agentId().equals(agentId(session))) {
            log.warn("agent claims id '{}' but verified id is '{}' — ignoring the claim",
                    hello.agentId(), agentId(session));
        }
        channel.deliver(frame);                                       // synchronous (RemoteEngine)
        jobGateway.ifPresent(jg -> jg.onFrame(tenantId(session), frame));  // durable jobs
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        channels.remove(session.getId());
        registry.unregisterBySession(session.getId());
        jobGateway.ifPresent(jg -> jg.onAgentDisconnected(tenantId(session), agentId(session)));
        log.info("agent socket closed: {} ({})", session.getId(), status);
    }

    private static String tenantId(WebSocketSession session) {
        return (String) session.getAttributes().get(AgentAuthInterceptor.ATTR_TENANT_ID);
    }

    private static String agentId(WebSocketSession session) {
        return (String) session.getAttributes().get(AgentAuthInterceptor.ATTR_AGENT_ID);
    }
}
