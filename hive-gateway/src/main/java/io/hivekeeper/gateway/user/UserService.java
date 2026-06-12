package io.hivekeeper.gateway.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Just-in-time identity for OIDC logins. The identity provider authenticates; on a validated token this
 * finds-or-creates the {@code app_user} keyed by (issuer, subject) and reads the user's organizations.
 * {@code app_user} is global (no RLS), so no tenant context is needed. The cross-organization membership
 * lookup goes through the {@code user_memberships} SECURITY DEFINER function, which safely bypasses the
 * per-tenant RLS to return only the given user's rows (a tenant-context query can only see one org at a
 * time). Only present under the {@code oidc} profile.
 */
@Service
@Profile("oidc")
public class UserService {

    public record AppUser(String userId, String email, String name) {
    }

    public record Membership(String tenantId, String tenantName, String status) {
    }

    private final JdbcTemplate jdbc;

    public UserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotently provisions the user from token claims; refreshes email/name on each login. */
    @Transactional
    public AppUser provision(String issuer, String subject, String email, String name) {
        String userId = jdbc.queryForObject(
                "insert into app_user (user_id, oidc_issuer, oidc_subject, email, name) values (?, ?, ?, ?, ?) "
                        + "on conflict (oidc_issuer, oidc_subject) "
                        + "do update set email = excluded.email, name = excluded.name returning user_id",
                String.class, "usr-" + UUID.randomUUID(), issuer, subject, email, name);
        return new AppUser(userId, email, name);
    }

    /** The organizations this user belongs to (for the org switcher). */
    @Transactional(readOnly = true)
    public List<Membership> memberships(String userId) {
        return jdbc.query("select tenant_id, tenant_name, status from user_memberships(?)",
                (rs, n) -> new Membership(rs.getString("tenant_id"), rs.getString("tenant_name"),
                        rs.getString("status")),
                userId);
    }
}
