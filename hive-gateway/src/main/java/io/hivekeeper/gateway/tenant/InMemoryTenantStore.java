package io.hivekeeper.gateway.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private final Map<String, Tenant> byId;
    private final Map<String, Tenant> byApiKey;
    private final Map<String, AgentEnrollment> byToken;
    private final Map<String, AgentEnrollment> byAgentId;

    public InMemoryTenantStore(@Value("${hivekeeper.demo-seed:false}") boolean demoSeed) {
        List<Tenant> tenants = demoSeed
                ? List.of(new Tenant("acme", "Acme Corp", "acme-key", "owner"),
                        new Tenant("globex", "Globex", "globex-key", "owner"))
                : List.of();
        List<AgentEnrollment> enrollments = demoSeed
                ? List.of(new AgentEnrollment("enroll-lab-agent", "lab-agent", "acme"))
                : List.of();

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
