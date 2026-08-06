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
 *
 * <h2>Why the entry remembers WHICH session owns it</h2>
 * An agent reconnects far faster than the gateway notices the old socket died. When the uplink drops
 * abruptly — a tunnel or NAT dropping the stream, which the agent sees as a bare {@code 1006} close with no
 * close frame — the gateway is left holding a HALF-OPEN socket: nothing arrives to close it until a write
 * fails or an idle timeout fires, potentially minutes later. The agent, meanwhile, is back in about a second
 * on a NEW session that legitimately overwrites this agent's entry.
 *
 * <p>So the stale close lands <i>after</i> the live registration. If eviction were unconditional it would
 * delete the entry the new session just installed, and the agent would be connected but invisible: absent
 * from {@code GET /api/agents}, shown OFFLINE in the console, and rejected as {@code agent_not_connected}
 * for every job — until its next reconnect, which may be hours away. Hence {@link #unregisterBySession}
 * evicts only when the closing session is still the one that owns the entry.
 */
@Component
class AgentRegistry {

    private record AgentKey(String tenantId, String agentId) {
    }

    /** The live connection for an agent, tagged with the session that owns it so a late close from a
     *  superseded session can be told apart from the current one. */
    private record Registration(String sessionId, RemoteEngine engine, PublicKey publicKey) {

        Registration withPublicKey(PublicKey key) {
            return new Registration(sessionId, engine, key);
        }
    }

    private final Map<AgentKey, Registration> byKey = new ConcurrentHashMap<>();
    private final Map<String, AgentKey> bySession = new ConcurrentHashMap<>();

    void register(String tenantId, String agentId, String sessionId, RemoteEngine engine) {
        AgentKey key = new AgentKey(tenantId, agentId);
        byKey.put(key, new Registration(sessionId, engine, null));
        bySession.put(sessionId, key);
    }

    /**
     * Records the agent's public key (from its verified mTLS cert), used to seal credentials TO it. Absent for
     * bearer-token (dev) connections, which present no certificate. Call after {@link #register}: it attaches
     * the key to the CURRENT registration, so a key arriving late for a session that has already been
     * superseded cannot overwrite the live one.
     */
    void registerPublicKey(String tenantId, String agentId, PublicKey publicKey) {
        if (publicKey == null) {
            return;
        }
        byKey.computeIfPresent(new AgentKey(tenantId, agentId), (k, current) -> current.withPublicKey(publicKey));
    }

    /**
     * Drops the session, and the agent with it — but ONLY if this session still owns the agent's entry. A close
     * from a session that a reconnect already replaced is discarded, leaving the live connection intact (see
     * the class note).
     *
     * @return true when the agent was actually evicted, i.e. this was the live session going down. False means
     *         the close was a straggler and the agent is still connected — callers must not treat it as a
     *         disconnect.
     */
    boolean unregisterBySession(String sessionId) {
        AgentKey key = bySession.remove(sessionId);
        if (key == null) {
            return false;
        }
        // computeIfPresent returning null removes the entry — atomically, and only for the owning session.
        // The flag is what distinguishes "we evicted it" from "it was already gone": both leave the map empty.
        boolean[] evicted = {false};
        byKey.computeIfPresent(key, (k, current) -> {
            if (!current.sessionId().equals(sessionId)) {
                return current;                       // superseded by a reconnect — leave the live entry alone
            }
            evicted[0] = true;
            return null;
        });
        return evicted[0];
    }

    Optional<RemoteEngine> engine(String tenantId, String agentId) {
        return Optional.ofNullable(byKey.get(new AgentKey(tenantId, agentId))).map(Registration::engine);
    }

    /** The agent's public key for sealing secrets to it, or empty if it connected without a certificate. */
    Optional<PublicKey> publicKey(String tenantId, String agentId) {
        return Optional.ofNullable(byKey.get(new AgentKey(tenantId, agentId))).map(Registration::publicKey);
    }

    Set<String> agentIds(String tenantId) {
        return byKey.keySet().stream()
                .filter(k -> k.tenantId().equals(tenantId))
                .map(AgentKey::agentId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
