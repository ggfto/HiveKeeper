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

    private static final RowMapper<Tenant> TENANT = (rs, n) -> new Tenant(
            rs.getString("tenant_id"), rs.getString("name"), rs.getString("operator_api_key"),
            rs.getString("operator_role"));
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
    public boolean markEnrollmentConsumed(String token) {
        if (token == null) {
            return false;
        }
        // Atomic one-time consumption: only the call that flips consumed_at from NULL wins (rows == 1).
        int rows = jdbc.update(
                "update agent_enrollment set consumed_at = now() where token = ? and consumed_at is null", token);
        return rows == 1;
    }

    @Override
    public boolean isAgentRevoked(String agentId) {
        if (agentId == null) {
            return false;
        }
        Boolean revoked = jdbc.query(
                "select revoked_at is not null from agent_enrollment where agent_id = ?",
                rs -> rs.next() && rs.getBoolean(1), agentId);
        return Boolean.TRUE.equals(revoked);
    }

    @Override
    public boolean revokeAgent(String tenantId, String agentId, String reason) {
        if (tenantId == null || agentId == null) {
            return false;
        }
        // Idempotent: stamp revoked_at (and the reason) for the agent in this tenant. A second revoke just
        // refreshes the timestamp; the row count (1) still means "the agent exists in this tenant".
        int rows = jdbc.update(
                "update agent_enrollment set revoked_at = now(), revoked_reason = ? "
                        + "where agent_id = ? and tenant_id = ?", reason, agentId, tenantId);
        return rows == 1;
    }

    @Override
    public Optional<String> reEnrollAgent(String tenantId, String agentId) {
        if (tenantId == null || agentId == null) {
            return Optional.empty();
        }
        // Issue a fresh one-time token and clear the revoked/consumed marks, so the agent (or a replacement
        // provisioned with this token) can bootstrap a new certificate. Rewrites the token PK in place.
        String newToken = "enroll-" + java.util.UUID.randomUUID();
        int rows = jdbc.update(
                "update agent_enrollment set token = ?, consumed_at = null, revoked_at = null, "
                        + "revoked_reason = null where agent_id = ? and tenant_id = ?",
                newToken, agentId, tenantId);
        return rows == 1 ? Optional.of(newToken) : Optional.empty();
    }

    @Override
    public Optional<Tenant> tenantByApiKey(String apiKey) {
        if (apiKey == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select tenant_id, name, operator_api_key, operator_role from tenant where operator_api_key = ?",
                TENANT, apiKey));
    }

    @Override
    public Optional<Tenant> tenant(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return first(jdbc.query(
                "select tenant_id, name, operator_api_key, operator_role from tenant where tenant_id = ?",
                TENANT, tenantId));
    }

    @Override
    public Optional<String> agentSiteId(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("select site_id from agent_enrollment where agent_id = ?", String.class, agentId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public List<Tenant> listAllTenants() {
        return jdbc.query("select tenant_id, name, operator_api_key, operator_role from tenant order by tenant_id",
                TENANT);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
