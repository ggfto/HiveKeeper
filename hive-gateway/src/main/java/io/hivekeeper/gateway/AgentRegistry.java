package io.hivekeeper.gateway;

import io.hivekeeper.protocol.RemoteEngine;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Tracks connected agents: agentId -&gt; the {@link RemoteEngine} bound to that agent's channel. This is
 * the in-memory v1; a full gateway persists agent identity + a per-agent job queue for redelivery.
 */
@Component
class AgentRegistry {

    private final Map<String, RemoteEngine> byAgentId = new ConcurrentHashMap<>();
    private final Map<String, String> agentBySession = new ConcurrentHashMap<>();

    void register(String agentId, String sessionId, RemoteEngine engine) {
        byAgentId.put(agentId, engine);
        agentBySession.put(sessionId, agentId);
    }

    void unregisterBySession(String sessionId) {
        String agentId = agentBySession.remove(sessionId);
        if (agentId != null) {
            byAgentId.remove(agentId);
        }
    }

    Optional<RemoteEngine> engine(String agentId) {
        return Optional.ofNullable(byAgentId.get(agentId));
    }

    Set<String> agentIds() {
        return Set.copyOf(byAgentId.keySet());
    }
}
