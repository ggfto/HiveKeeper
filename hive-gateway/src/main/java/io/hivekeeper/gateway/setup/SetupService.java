package io.hivekeeper.gateway.setup;

import io.hivekeeper.gateway.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * First-run bootstrap: turn a fresh, uninitialized gateway into one with a first organization and a first
 * admin. The admin identity is created in Keycloak (the IdP authenticates; we authorize), then we create the
 * tenant (org) and grant that user OWNER on it. Guarded two ways: a one-time {@code setupToken} generated in
 * memory at startup (printed to the server console, so completing setup needs server access) and a hard
 * "only while uninitialized" check — once any tenant exists, setup is locked.
 *
 * <p>Only present under the {@code oidc} profile (it bootstraps a Keycloak identity); it also needs the
 * {@code postgres} profile for the JdbcTemplate it writes through.
 */
@Service
@Profile("oidc")
public class SetupService {

    public record SetupResult(String tenantId) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final KeycloakAdminClient keycloak;
    private final UserService users;
    private final String issuer;
    private final String setupToken;

    public SetupService(JdbcTemplate jdbc, KeycloakAdminClient keycloak, UserService users,
                        @Value("${hivekeeper.oidc.issuer}") String issuer) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.users = users;
        this.issuer = issuer;
        this.setupToken = randomToken();
    }

    /** Whether any organization exists yet. While false, setup is open; once true, it is locked. */
    public boolean isInitialized() {
        Integer n = jdbc.queryForObject("select count(*) from tenant", Integer.class);
        return n != null && n > 0;
    }

    /** The one-time token required to complete setup (printed to the console on first boot). */
    public String setupToken() {
        return setupToken;
    }

    /**
     * Compare the presented token in constant time. {@code String.equals} returns as soon as two bytes differ,
     * so how long the answer takes leaks how much of the token was right — enough, over enough attempts, to
     * recover it a character at a time. This is the one unauthenticated endpoint that writes to the database,
     * so it is worth not leaking anything at all.
     */
    private boolean isSetupToken(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), setupToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create the first org + admin. Validates the setup token and that the gateway is still uninitialized,
     * creates the Keycloak user (whose id becomes the admin's OIDC subject), then writes the tenant, the
     * app_user, the membership and an OWNER org grant in one transaction. The Keycloak user is created first
     * because its id is needed for the app_user row; if the DB writes then fail the transaction rolls back but
     * the Keycloak user remains (retry with a different admin username, or remove it in Keycloak).
     */
    @Transactional
    public SetupResult setup(String token, String orgName, String username, String password, String email,
                             String displayName) {
        if (!isSetupToken(token)) {
            throw new SetupException(403, "invalid or missing setup token");
        }
        if (isBlank(orgName)) {
            throw new SetupException(400, "an organization name is required");
        }
        if (isBlank(username) || isBlank(password)) {
            throw new SetupException(400, "an admin username and password are required");
        }
        if (isInitialized()) {
            throw new SetupException(409, "this gateway is already initialized");
        }

        String name = isBlank(displayName) ? username.trim() : displayName.trim();
        String kcUserId = keycloak.createUser(username.trim(), email, password, name);

        String tenantId = slug(orgName);
        // A random operator key keeps the not-null/unique column satisfied (for automation parity); this org
        // signs in via OIDC, so the key is unused unless the owner later rotates it to a known value.
        String operatorKey = "svc-" + randomToken();
        jdbc.update(
                "insert into tenant (tenant_id, name, operator_api_key, operator_role) values (?, ?, ?, 'owner')",
                tenantId, orgName.trim(), operatorKey);

        UserService.AppUser user = users.provision(issuer, kcUserId, email, name);

        // membership + role_grant are RLS-protected: the policy's WITH CHECK compares tenant_id to the
        // transaction-local app.current_tenant, so set it before inserting (the same pattern the other services
        // use). tenant + app_user are global (no RLS), so they were written above without it.
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);

        String membershipId = "mb-" + UUID.randomUUID();
        jdbc.update("insert into membership (membership_id, user_id, tenant_id) values (?, ?, ?)",
                membershipId, user.userId(), tenantId);
        jdbc.update("insert into role_grant (grant_id, membership_id, tenant_id, role, scope_type, scope_id) "
                        + "values (?, ?, ?, 'owner', 'org', null)",
                "g-" + UUID.randomUUID(), membershipId, tenantId);

        return new SetupResult(tenantId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** A url/header-safe tenant id from the org name; falls back to a generated id if nothing usable remains. */
    static String slug(String orgName) {
        String s = orgName.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "org-" + UUID.randomUUID() : s;
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
