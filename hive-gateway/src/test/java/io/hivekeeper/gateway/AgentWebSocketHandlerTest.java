package io.hivekeeper.gateway;

import io.hivekeeper.gateway.fleet.FleetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The socket lifecycle's side effects: stamping {@code last_seen}, and — the part that matters most —
 * refusing to treat a close from a superseded session as a disconnect. See {@link AgentRegistry} for why a
 * stale close arrives after the reconnect that replaced it.
 */
class AgentWebSocketHandlerTest {

    private AgentRegistry registry;
    private FleetService fleet;
    private JobGateway jobGateway;
    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
        fleet = mock(FleetService.class);
        jobGateway = mock(JobGateway.class);
        handler = new AgentWebSocketHandler(registry, Optional.of(jobGateway),
                mock(BackupDestinationProvisioner.class), Optional.of(fleet));
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AgentAuthInterceptor.ATTR_TENANT_ID, "acme");
        attributes.put(AgentAuthInterceptor.ATTR_AGENT_ID, "lab-agent");
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    void stampsLastSeenWhenTheAgentConnects() {
        handler.afterConnectionEstablished(session("s1"));

        verify(fleet).markAgentSeen("acme", "lab-agent");
    }

    @Test
    void stampsLastSeenAgainWhenItReallyDisconnects() {
        WebSocketSession s1 = session("s1");
        handler.afterConnectionEstablished(s1);

        handler.afterConnectionClosed(s1, CloseStatus.NO_CLOSE_FRAME);

        // Once for the connect, once for the disconnect — the second is the one an offline agent's card reads.
        verify(fleet, org.mockito.Mockito.times(2)).markAgentSeen("acme", "lab-agent");
        verify(jobGateway).onAgentDisconnected("acme", "lab-agent");
    }

    @Test
    void aSupersededCloseIsNotADisconnect() {
        // The abrupt-drop sequence: s1 dies without a close frame, the agent reconnects as s2, and only then
        // does the gateway notice s1. That straggler must not evict the live agent or report a disconnect.
        handler.afterConnectionEstablished(session("s1"));
        handler.afterConnectionEstablished(session("s2"));

        handler.afterConnectionClosed(session("s1"), CloseStatus.NO_CLOSE_FRAME);

        assertTrue(registry.engine("acme", "lab-agent").isPresent(), "the agent is still connected on s2");
        assertTrue(registry.agentIds("acme").contains("lab-agent"), "and still listed as online");
        verify(jobGateway, never()).onAgentDisconnected(any(), any());
        // Only the two connects stamped it: recording "last seen" now would name a time the agent was
        // demonstrably still on the wire.
        verify(fleet, org.mockito.Mockito.times(2)).markAgentSeen("acme", "lab-agent");
    }

    @Test
    void aFailingLastSeenWriteNeverCostsTheConnection() {
        // last_seen is a console column. A flaky database must not be able to refuse an agent's uplink.
        doThrow(new RuntimeException("db down")).when(fleet).markAgentSeen(any(), any());
        WebSocketSession s1 = session("s1");

        assertDoesNotThrow(() -> handler.afterConnectionEstablished(s1));

        assertTrue(registry.engine("acme", "lab-agent").isPresent(), "the agent is connected regardless");
        assertDoesNotThrow(() -> handler.afterConnectionClosed(s1, CloseStatus.NORMAL));
    }

    @Test
    void worksWithoutAFleetService() {
        // The no-database mode has no FleetService at all; the lifecycle must not depend on one.
        AgentWebSocketHandler noFleet = new AgentWebSocketHandler(registry, Optional.empty(),
                mock(BackupDestinationProvisioner.class), Optional.empty());
        WebSocketSession s1 = session("s1");

        assertDoesNotThrow(() -> noFleet.afterConnectionEstablished(s1));
        assertDoesNotThrow(() -> noFleet.afterConnectionClosed(s1, CloseStatus.NORMAL));
    }
}
