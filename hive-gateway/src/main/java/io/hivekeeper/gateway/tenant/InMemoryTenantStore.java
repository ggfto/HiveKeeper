package io.hivekeeper.gateway.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory tenant store (the default when no database is configured), seeded with two demo tenants
 * and one enrolled agent. The {@code postgres} profile swaps in {@code PostgresTenantStore} — callers
 * depend only on {@link TenantStore}.
 */
@Component
@Profile("!postgres")
public class InMemoryTenantStore implements TenantStore {

    private final Map<String, Tenant> byId;
    private final Map<String, Tenant> byApiKey;
    private final Map<String, AgentEnrollment> byToken;
    private final Map<String, AgentEnrollment> byAgentId;

    public InMemoryTenantStore() {
        List<Tenant> tenants = List.of(
                new Tenant("acme", "Acme Corp", "acme-key"),
                new Tenant("globex", "Globex", "globex-key"));
        List<AgentEnrollment> enrollments = List.of(
                new AgentEnrollment("enroll-lab-agent", "lab-agent", "acme"));

        this.byId = tenants.stream().collect(Collectors.toMap(Tenant::tenantId, Function.identity()));
        this.byApiKey = tenants.stream().collect(Collectors.toMap(Tenant::operatorApiKey, Function.identity()));
        this.byToken = enrollments.stream().collect(Collectors.toMap(AgentEnrollment::token, Function.identity()));
        this.byAgentId = enrollments.stream().collect(Collectors.toMap(AgentEnrollment::agentId, Function.identity()));
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
    public Optional<Tenant> tenantByApiKey(String apiKey) {
        return apiKey == null ? Optional.empty() : Optional.ofNullable(byApiKey.get(apiKey));
    }

    @Override
    public Optional<Tenant> tenant(String tenantId) {
        return tenantId == null ? Optional.empty() : Optional.ofNullable(byId.get(tenantId));
    }
}
