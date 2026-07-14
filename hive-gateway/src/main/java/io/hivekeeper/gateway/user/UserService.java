package io.hivekeeper.gateway.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
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

    /**
     * The user behind a validated token, or empty if we do not know them — a pure lookup, which never writes.
     *
     * <p>It used to provision on first sight, and that has to stop now that an identity provider can be
     * brokered. With "Sign in with GitHub" enabled, <b>every GitHub account on earth</b> can authenticate
     * against the realm and present a perfectly valid token. Creating a row for each one would let any stranger
     * grow this table without ever being authorized for anything — they would get a 403 immediately afterwards,
     * having already written to the database.
     *
     * <p>Rows are now written only where somebody is deliberately admitted: first-run setup, and
     * {@code MemberService} (which creates a login, or admits an existing account). So an unknown token means
     * an unknown person, and the caller treats that exactly as it treats a non-member.
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> resolve(Jwt jwt) {
        return jdbc.query(
                "select user_id, email, name from app_user where oidc_issuer = ? and oidc_subject = ?",
                (rs, n) -> new AppUser(rs.getString("user_id"), rs.getString("email"), rs.getString("name")),
                String.valueOf(jwt.getIssuer()), jwt.getSubject())
                .stream().findFirst();
    }

    /** Whether the user is an ACTIVE member of the organization. Uses the SECURITY DEFINER lookup so no
     *  per-tenant context juggling is needed. */
    @Transactional(readOnly = true)
    public boolean isMember(String tenantId, String userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from user_memberships(?) where tenant_id = ? and status = 'active'",
                Integer.class, userId, tenantId);
        return n != null && n > 0;
    }
}
