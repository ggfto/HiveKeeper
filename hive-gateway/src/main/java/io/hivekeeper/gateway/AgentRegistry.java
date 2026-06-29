package io.hivekeeper.gateway;

import io.hivekeeper.protocol.RemoteEngine;
import org.springframework.stereotype.Component;
import java.security.PublicKey;
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
    // The agent's public key (from its verified mTLS cert), used to seal credentials TO it. Absent for
    // bearer-token (dev) connections, which present no certificate.
    private final Map<AgentKey, PublicKey> publicKeys = new ConcurrentHashMap<>();

    void register(String tenantId, String agentId, String sessionId, RemoteEngine engine) {
        AgentKey key = new AgentKey(tenantId, agentId);
        byKey.put(key, engine);
        bySession.put(sessionId, key);
    }

    /** Records the agent's public key (mTLS only). Call after {@link #register}. */
    void registerPublicKey(String tenantId, String agentId, PublicKey publicKey) {
        if (publicKey != null) {
            publicKeys.put(new AgentKey(tenantId, agentId), publicKey);
        }
    }

    void unregisterBySession(String sessionId) {
        AgentKey key = bySession.remove(sessionId);
        if (key != null) {
            byKey.remove(key);
            publicKeys.remove(key);
        }
    }

    Optional<RemoteEngine> engine(String tenantId, String agentId) {
        return Optional.ofNullable(byKey.get(new AgentKey(tenantId, agentId)));
    }

    /** The agent's public key for sealing secrets to it, or empty if it connected without a certificate. */
    Optional<PublicKey> publicKey(String tenantId, String agentId) {
        return Optional.ofNullable(publicKeys.get(new AgentKey(tenantId, agentId)));
    }

    Set<String> agentIds(String tenantId) {
        return byKey.keySet().stream()
                .filter(k -> k.tenantId().equals(tenantId))
                .map(AgentKey::agentId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
