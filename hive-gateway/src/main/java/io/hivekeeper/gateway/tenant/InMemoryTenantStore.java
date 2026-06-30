package io.hivekeeper.gateway.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tenant store (the default when no database is configured). It is EMPTY unless
 * {@code hivekeeper.demo-seed=true} (set by the {@code demo} profile), in which case it is seeded with two
 * public demo tenants and one enrolled agent for the local stack. This gating matters: the seed keys
 * ({@code acme-key}, {@code globex-key}, {@code enroll-lab-agent}) are well-known and in source — shipping them
 * active by default would hand owner rights to anyone. The {@code postgres} profile swaps in
 * {@code PostgresTenantStore}; callers depend only on {@link TenantStore}.
 */
@Component
@Profile("!postgres")
public class InMemoryTenantStore implements TenantStore {

    private final Map<String, Tenant> byId = new ConcurrentHashMap<>();
    private final Map<String, Tenant> byApiKey = new ConcurrentHashMap<>();
    private final Map<String, AgentEnrollment> byToken = new ConcurrentHashMap<>();
    private final Map<String, AgentEnrollment> byAgentId = new ConcurrentHashMap<>();
    private final java.util.Set<String> consumedTokens = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> revokedAgents = ConcurrentHashMap.newKeySet();

    public InMemoryTenantStore(@Value("${hivekeeper.demo-seed:false}") boolean demoSeed,
                               @Value("${hivekeeper.solo:false}") boolean solo) {
        List<Tenant> tenants = new ArrayList<>();
        List<AgentEnrollment> enrollments = new ArrayList<>();
        if (demoSeed) {
            tenants.add(new Tenant("acme", "Acme Corp", "acme-key", "owner"));
            tenants.add(new Tenant("globex", "Globex", "globex-key", "owner"));
            enrollments.add(new AgentEnrollment("enroll-lab-agent", "lab-agent", "acme"));
        }
        // Solo mode: one implicit local tenant + one local agent the user runs against their AP. AccessGuard
        // grants the owner of this tenant to every request, so the operator-key here is unused (just satisfies
        // the not-null/unique column).
        if (solo) {
            tenants.add(new Tenant("local", "Local", "local-key", "owner"));
            enrollments.add(new AgentEnrollment("enroll-local", "local-agent", "local"));
        }

        for (Tenant t : tenants) {
            byId.put(t.tenantId(), t);
            byApiKey.put(t.operatorApiKey(), t);
        }
        for (AgentEnrollment e : enrollments) {
            byToken.put(e.token(), e);
            byAgentId.put(e.agentId(), e);
        }
    }

    @Override
    public Optional<AgentEnrollment> enrollmentByToken(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(byToken.get(token));
    }

    @Override
    public Optional<AgentEnrollment> enrollmentByAgentId(String agentId) {
        return agentId == null ? Optional.empty() : Optional.ofNullable(byAgentId.get(agentId));
    }

    @Override
    public boolean markEnrollmentConsumed(String token) {
        // Win only if the token is a real enrollment AND this is the first time it is consumed.
        return token != null && byToken.containsKey(token) && consumedTokens.add(token);
    }

    @Override
    public boolean isAgentRevoked(String agentId) {
        return agentId != null && revokedAgents.contains(agentId);
    }

    @Override
    public synchronized boolean revokeAgent(String tenantId, String agentId, String reason) {
        AgentEnrollment enrollment = byAgentId.get(agentId);
        if (enrollment == null || !enrollment.tenantId().equals(tenantId)) {
            return false;
        }
        revokedAgents.add(agentId);   // idempotent
        return true;
    }

    @Override
    public synchronized Optional<String> reEnrollAgent(String tenantId, String agentId) {
        AgentEnrollment enrollment = byAgentId.get(agentId);
        if (enrollment == null || !enrollment.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        // Issue a fresh one-time token (replacing the old one) and clear the revoked/consumed marks so the
        // agent — or a replacement provisioned with this token — can bootstrap a new certificate.
        String newToken = "enroll-" + UUID.randomUUID();
        AgentEnrollment renewed = new AgentEnrollment(newToken, agentId, tenantId);
        byToken.remove(enrollment.token());
        consumedTokens.remove(enrollment.token());
        byToken.put(newToken, renewed);
        byAgentId.put(agentId, renewed);
        revokedAgents.remove(agentId);
        return Optional.of(newToken);
    }

    @Override
    public Optional<Tenant> tenantByApiKey(String apiKey) {
        return apiKey == null ? Optional.empty() : Optional.ofNullable(byApiKey.get(apiKey));
    }

    @Override
    public Optional<Tenant> tenant(String tenantId) {
        return tenantId == null ? Optional.empty() : Optional.ofNullable(byId.get(tenantId));
    }
}
