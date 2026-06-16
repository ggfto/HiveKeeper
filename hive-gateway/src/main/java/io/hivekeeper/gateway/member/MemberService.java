package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.KeycloakAdminClient;
import io.hivekeeper.gateway.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Steady-state organization membership: list the people in an org, add a teammate (a Keycloak login + an
 * app_user + an active membership + one org-scoped role grant), change someone's org role, or remove them.
 * This mirrors {@link io.hivekeeper.gateway.setup.SetupService}'s identity writes, but for the running system
 * rather than first-run bootstrap. Every write first sets the transaction-local tenant context the RLS
 * policies key off, so a caller can only ever read or change their own org's membership/role_grant rows — the
 * database is the hard wall between organizations; the controller layers on who-may-call authorization.
 *
 * <p>Only present under the {@code oidc} profile (it provisions Keycloak identities); like SetupService it
 * also needs the {@code postgres} profile for the JdbcTemplate it writes through.
 */
@Service
@Profile("oidc")
public class MemberService {

    /** A person in the org: their global user id + identity, membership status, and org-scoped role (null only
     *  if they somehow hold solely narrower site/group grants — the add flow always grants an org role). */
    public record Member(String userId, String email, String name, String status, String role) {
    }

    private static final RowMapper<Member> MEMBER = (rs, n) -> new Member(
            rs.getString("user_id"), rs.getString("email"), rs.getString("name"),
            rs.getString("status"), rs.getString("role"));

    private final JdbcTemplate jdbc;
    private final KeycloakAdminClient keycloak;
    private final UserService users;
    private final String issuer;

    public MemberService(JdbcTemplate jdbc, KeycloakAdminClient keycloak, UserService users,
                         @Value("${hivekeeper.oidc.issuer}") String issuer) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.users = users;
        this.issuer = issuer;
    }

    /** The org's members with their status and org role. RLS scopes the membership/role_grant rows to the
     *  tenant; app_user is global but the join through membership keeps the result to this org's people. */
    @Transactional(readOnly = true)
    public List<Member> list(String tenantId) {
        setTenant(tenantId);
        return jdbc.query(
                "select u.user_id, u.email, u.name, m.status, rg.role "
                        + "from membership m "
                        + "join app_user u on u.user_id = m.user_id "
                        + "left join role_grant rg on rg.membership_id = m.membership_id and rg.scope_type = 'org' "
                        + "order by lower(coalesce(u.email, u.name, u.user_id))",
                MEMBER);
    }

    /**
     * Add a teammate: create their Keycloak login with a temporary password (UPDATE_PASSWORD required action,
     * so they pick their own at first sign-in), provision the app_user keyed by the new Keycloak subject, then
     * write an active membership + a single org-scoped role grant. Returns the new user id. A duplicate Keycloak
     * username surfaces as a {@link io.hivekeeper.gateway.setup.KeycloakAdminException} (the controller maps it
     * to 409). Keycloak is created first because its id becomes the app_user's OIDC subject; if the DB writes
     * then fail the transaction rolls back but the Keycloak user remains (retry with a different username).
     */
    @Transactional
    public String add(String tenantId, String username, String email, String password, String displayName,
                      Role role) {
        String name = (displayName == null || displayName.isBlank()) ? username : displayName.trim();
        String kcUserId = keycloak.createUser(username, email, password, name, true);
        UserService.AppUser user = users.provision(issuer, kcUserId, email, name);

        setTenant(tenantId);
        String membershipId = "mb-" + UUID.randomUUID();
        jdbc.update("insert into membership (membership_id, user_id, tenant_id) values (?, ?, ?)",
                membershipId, user.userId(), tenantId);
        jdbc.update("insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) "
                        + "values (?, ?, ?, ?, 'org', null)",
                "g-" + UUID.randomUUID(), membershipId, tenantId, role.name().toLowerCase());
        return user.userId();
    }

    /** Set a member's single org-scoped role (updating their existing org grant, or inserting one if somehow
     *  absent). Returns false if the user is not a member of the org. */
    @Transactional
    public boolean setRole(String tenantId, String userId, Role role) {
        setTenant(tenantId);
        Optional<String> membershipId = membershipId(userId);
        if (membershipId.isEmpty()) {
            return false;
        }
        int updated = jdbc.update("update role_grant set role = ? where membership_id = ? and scope_type = 'org'",
                role.name().toLowerCase(), membershipId.get());
        if (updated == 0) {
            jdbc.update("insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) "
                            + "values (?, ?, ?, ?, 'org', null)",
                    "g-" + UUID.randomUUID(), membershipId.get(), tenantId, role.name().toLowerCase());
        }
        return true;
    }

    /** Remove a member from the org: delete their membership (its role grants cascade). Returns false if the
     *  user was not a member. The Keycloak login is intentionally left intact — it may belong to other orgs. */
    @Transactional
    public boolean remove(String tenantId, String userId) {
        setTenant(tenantId);
        return jdbc.update("delete from membership where user_id = ?", userId) > 0;
    }

    /** A member's current org-scoped role, or empty if they are not a member of the org. */
    @Transactional(readOnly = true)
    public Optional<Role> orgRole(String tenantId, String userId) {
        setTenant(tenantId);
        List<String> roles = jdbc.queryForList(
                "select rg.role from role_grant rg join membership m on rg.membership_id = m.membership_id "
                        + "where m.user_id = ? and rg.scope_type = 'org'", String.class, userId);
        return roles.isEmpty() ? Optional.empty() : Optional.of(Role.of(roles.get(0)));
    }

    /** How many ACTIVE members hold the org owner role — used to refuse demoting or removing the last owner. */
    @Transactional(readOnly = true)
    public int ownerCount(String tenantId) {
        setTenant(tenantId);
        Integer n = jdbc.queryForObject(
                "select count(*) from role_grant rg join membership m on rg.membership_id = m.membership_id "
                        + "where rg.scope_type = 'org' and rg.role = 'owner' and m.status = 'active'",
                Integer.class);
        return n == null ? 0 : n;
    }

    private Optional<String> membershipId(String userId) {
        List<String> ids = jdbc.queryForList(
                "select membership_id from membership where user_id = ?", String.class, userId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }
}
