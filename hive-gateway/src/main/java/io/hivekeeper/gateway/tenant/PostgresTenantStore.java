package io.hivekeeper.gateway.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed tenant store (the {@code postgres} profile). Tenant + enrollment lookups are
 * cross-tenant by nature (resolve an API key / token to its tenant), so these tables carry no RLS;
 * tenant isolation is enforced by RLS on the per-tenant data (see operation_log).
 */
@Component
@Profile("postgres")
public class PostgresTenantStore implements TenantStore {

    private static final RowMapper<Tenant> TENANT =
            (rs, n) -> new Tenant(rs.getString("tenant_id"), rs.getString("name"), rs.getString("operator_api_key"));
    private static final RowMapper<AgentEnrollment> ENROLLMENT =
            (rs, n) -> new AgentEnrollment(rs.getString("token"), rs.getString("agent_id"), rs.getString("tenant_id"));

    private final JdbcTemplate jdbc;

    public PostgresTenantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AgentEnrollment> enrollmentByToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select token, agent_id, tenant_id from agent_enrollment where token = ?", ENROLLMENT, token));
    }

    @Override
    public Optional<AgentEnrollment> enrollmentByAgentId(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select token, agent_id, tenant_id from agent_enrollment where agent_id = ?", ENROLLMENT, agentId));
    }

    @Override
    public Optional<Tenant> tenantByApiKey(String apiKey) {
        if (apiKey == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select tenant_id, name, operator_api_key from tenant where operator_api_key = ?", TENANT, apiKey));
    }

    @Override
    public Optional<Tenant> tenant(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select tenant_id, name, operator_api_key from tenant where tenant_id = ?", TENANT, tenantId));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
