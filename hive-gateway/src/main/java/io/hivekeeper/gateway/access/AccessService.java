package io.hivekeeper.gateway.access;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Loads a user's scoped role grants within an organization and answers authorization questions via the
 * pure {@link Permissions} resolver. Every read sets the transaction-local tenant context the RLS policy
 * keys off, so the database guarantees a user's grants are only ever read inside their own organization.
 * Only present under the {@code postgres} profile.
 */
@Service
@Profile("postgres")
public class AccessService {

    private final JdbcTemplate jdbc;

    public AccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The grants a user holds in the given organization (empty if they are not a member). */
    @Transactional(readOnly = true)
    public List<Grant> grantsFor(String tenantId, String userId) {
        setTenant(tenantId);
        // A suspended/invited membership confers no grants — only an active one. (RLS already scopes both
        // tables to the current tenant, which is why no tenant_id predicate is needed here.)
        return jdbc.query(
                "select rg.role, rg.scope_type, rg.scope_id from role_grant rg "
                        + "join membership m on rg.membership_id = m.membership_id "
                        + "where m.user_id = ? and m.status = 'active'",
                (rs, n) -> new Grant(Role.of(rs.getString("role")),
                        ScopeType.of(rs.getString("scope_type")), rs.getString("scope_id")),
                userId);
    }

    /** The user's effective role on a resource, or empty if no grant reaches it.
     *  Transactional so the internal {@link #grantsFor} call (a self-invocation that bypasses the proxy)
     *  still runs in one transaction — the {@code set_config} tenant context the RLS policy reads is
     *  transaction-local, so it and the grant query must share a transaction. */
    @Transactional(readOnly = true)
    public Optional<Role> effectiveRole(String tenantId, String userId, ResourceScope resource) {
        return Permissions.effectiveRole(grantsFor(tenantId, userId), resource);
    }

    /** True if the user has at least {@code required} on the resource. */
    @Transactional(readOnly = true)
    public boolean allows(String tenantId, String userId, Role required, ResourceScope resource) {
        return Permissions.allows(grantsFor(tenantId, userId), required, resource);
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }
}
