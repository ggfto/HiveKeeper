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
    private final Optional<io.hivekeeper.gateway.fleet.FleetService> fleet;

    AgentWebSocketHandler(AgentRegistry registry, Optional<JobGateway> jobGateway,
                          BackupDestinationProvisioner backupDestinations,
                          Optional<io.hivekeeper.gateway.fleet.FleetService> fleet) {
        this.registry = registry;
        this.jobGateway = jobGateway;
        this.backupDestinations = backupDestinations;
        this.fleet = fleet;
    }

    /**
     * Records that we have the agent on the wire right now. Deliberately swallows every failure: this is
     * bookkeeping for a console column, and a flaky database must not be able to sever — or refuse — an
     * agent's uplink. Losing a timestamp is a cosmetic regression; losing the connection is an outage.
     */
    private void markSeen(String tenantId, String agentId) {
        fleet.ifPresent(f -> {
            try {
                f.markAgentSeen(tenantId, agentId);
            } catch (RuntimeException e) {
                log.warn("could not record last-seen for agent '{}': {}", agentId, e.getMessage());
            }
        });
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
        markSeen(tenantId, agentId);
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
        // Only a close from the session that still OWNS the agent is a real disconnect. When an abrupt drop
        // leaves the gateway holding a half-open socket, the agent is already back on a new session by the time
        // this fires; treating that straggler as a disconnect would evict the live agent from job dispatch and
        // strand it as "offline" until its next reconnect. See AgentRegistry.
        boolean wasLive = registry.unregisterBySession(session.getId());
        if (!wasLive) {
            log.info("ignoring close of superseded agent socket {} ({}) — '{}' is connected on a newer session",
                    session.getId(), status, agentId(session));
            return;
        }
        // Stamped here too, and only on a REAL disconnect: this is the moment the value actually has to
        // answer for, since it is offline agents whose "last seen" anyone reads. Doing it on a superseded
        // close would instead record a time the agent was demonstrably still connected.
        markSeen(tenantId(session), agentId(session));
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
