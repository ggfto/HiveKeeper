package io.hivekeeper.gateway;

import io.hivekeeper.protocol.RemoteEngine;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks connected agents, keyed by (tenantId, agentId) so lookups are always tenant-scoped — an
 * operator can never reach another tenant's agent. v1 is in-memory; a full gateway persists agent
 * identity + a per-agent job queue for redelivery.
 */
@Component
class AgentRegistry {

    private record AgentKey(String tenantId, String agentId) {
    }

    private final Map<AgentKey, RemoteEngine> byKey = new ConcurrentHashMap<>();
    private final Map<String, AgentKey> bySession = new ConcurrentHashMap<>();

    void register(String tenantId, String agentId, String sessionId, RemoteEngine engine) {
        AgentKey key = new AgentKey(tenantId, agentId);
        byKey.put(key, engine);
        bySession.put(sessionId, key);
    }

    void unregisterBySession(String sessionId) {
        AgentKey key = bySession.remove(sessionId);
        if (key != null) {
            byKey.remove(key);
        }
    }

    Optional<RemoteEngine> engine(String tenantId, String agentId) {
        return Optional.ofNullable(byKey.get(new AgentKey(tenantId, agentId)));
    }

    Set<String> agentIds(String tenantId) {
        return byKey.keySet().stream()
                .filter(k -> k.tenantId().equals(tenantId))
                .map(AgentKey::agentId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
