package io.hivekeeper.gateway.setup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal Keycloak Admin API client — only what first-run setup needs: create the very first realm admin user
 * with a password. It authenticates to the admin realm (default {@code master}, client {@code admin-cli}) with
 * the configured admin credentials to get a short-lived token, then creates the user in the application realm
 * and returns the new user's id (which becomes the {@code sub} of the JWTs they will later sign in with).
 *
 * <p>Only present under the {@code oidc} profile. The admin credentials are configuration the operator
 * provides for their own Keycloak; they are never exposed to clients.
 */
@Component
@Profile("oidc")
public class KeycloakAdminClient {

    private final RestClient http = RestClient.create();
    private final String baseUrl;
    private final String realm;
    private final String adminRealm;
    private final String adminClient;
    private final String adminUsername;
    private final String adminPassword;

    public KeycloakAdminClient(
            @Value("${hivekeeper.keycloak.base-url:http://localhost:8081}") String baseUrl,
            @Value("${hivekeeper.keycloak.realm:hivekeeper}") String realm,
            @Value("${hivekeeper.keycloak.admin-realm:master}") String adminRealm,
            @Value("${hivekeeper.keycloak.admin-client:admin-cli}") String adminClient,
            @Value("${hivekeeper.keycloak.admin-username:admin}") String adminUsername,
            @Value("${hivekeeper.keycloak.admin-password:admin}") String adminPassword) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.realm = realm;
        this.adminRealm = adminRealm;
        this.adminClient = adminClient;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    /**
     * Create a realm user with a permanent password and no pending actions, returning their Keycloak id. Used
     * by first-run setup for the very first admin, who must be able to sign in immediately.
     */
    public String createUser(String username, String email, String password, String displayName) {
        return createUser(username, email, password, displayName, false);
    }

    /**
     * Create a realm user and return their Keycloak id. A display name is split into first/last because
     * Keycloak's user-profile requires a first + last name — a user created without them is "not fully set up"
     * and cannot sign in until they complete a profile prompt. When {@code temporary} is true the password is
     * marked temporary and an {@code UPDATE_PASSWORD} required action is set, so the new teammate is forced to
     * choose their own password at first sign-in (the admin only ever sets a throwaway one); when false the
     * password is permanent and no action is pending (the first-run admin).
     */
    public String createUser(String username, String email, String password, String displayName,
                             boolean temporary) {
        String dn = (displayName == null || displayName.isBlank()) ? username : displayName.trim();
        String[] parts = dn.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : firstName;

        String token = adminToken();
        try {
            ResponseEntity<Void> created = http.post()
                    .uri(baseUrl + "/admin/realms/" + realm + "/users")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "email", email == null ? "" : email,
                            "firstName", firstName,
                            "lastName", lastName,
                            "enabled", true,
                            "emailVerified", true,
                            "requiredActions", temporary ? List.of("UPDATE_PASSWORD") : List.of(),
                            "credentials", List.of(Map.of(
                                    "type", "password", "value", password, "temporary", temporary))))
                    .retrieve()
                    .toBodilessEntity();
            String location = created.getHeaders().getFirst("Location");
            if (location == null || !location.contains("/")) {
                throw new KeycloakAdminException("Keycloak did not return the new user's id");
            }
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new KeycloakAdminException("a user '" + username + "' already exists in realm " + realm);
            }
            throw new KeycloakAdminException("creating the Keycloak user failed: HTTP " + e.getStatusCode().value());
        }
    }

    private String adminToken() {
        String form = "grant_type=password"
                + "&client_id=" + enc(adminClient)
                + "&username=" + enc(adminUsername)
                + "&password=" + enc(adminPassword);
        try {
            Map<?, ?> body = http.post()
                    .uri(baseUrl + "/realms/" + adminRealm + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object token = body == null ? null : body.get("access_token");
            if (token == null) {
                throw new KeycloakAdminException("Keycloak admin token response had no access_token");
            }
            return token.toString();
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException("Keycloak admin authentication failed: HTTP " + e.getStatusCode().value()
                    + " (check hivekeeper.keycloak.admin-username/password)");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
