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
import java.util.Optional;

/**
 * Authentik Admin API client — only what first-run setup needs: create users with passwords. It uses the
 * operator's API token to create users in the configured Authentik instance, then returns the user's pk
 * (which becomes the {@code sub} of the JWTs they will later sign in with).
 *
 * <p>Only present under the {@code oidc-authentik} profile. The API token is configuration the operator
 * provides for their own Authentik instance; it is never exposed to clients.
 *
 * <p>Authentik API reference: https://docs.goauthentik.io/developer-docs/api
 */
@Component
@Profile("oidc-authentik")
public class AuthentikAdminClient implements IdpAdminClient {

    private final RestClient http = RestClient.create();
    private final String baseUrl;
    private final String apiToken;

    public AuthentikAdminClient(
            @Value("${hivekeeper.authentik.base-url:http://localhost:9000}") String baseUrl,
            @Value("${hivekeeper.authentik.api-token}") String apiToken) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiToken = apiToken;
    }

    /**
     * Create a user with a permanent password, returning their Authentik pk. Used by first-run setup for the
     * very first admin, who must be able to sign in immediately.
     */
    @Override
    public String createUser(String username, String email, String password, String displayName) {
        return createUser(username, email, password, displayName, false);
    }

    /**
     * Create a user and return their Authentik pk. When {@code temporary} is true the user is marked to change
     * password at next login; when false the password is permanent (first-run admin).
     *
     * <p>Authentik does not have a built-in "temporary password" flag like Keycloak. Instead, we set a custom
     * attribute that could be checked by a flow policy if needed, or simply let the admin rotate it manually.
     */
    @Override
    public String createUser(String username, String email, String password, String displayName, boolean temporary) {
        String name = (displayName == null || displayName.isBlank()) ? username : displayName.trim();

        try {
            // Step 1: Create the user
            Map<String, Object> userPayload = Map.of(
                    "username", username,
                    "name", name,
                    "email", email == null ? "" : email,
                    "is_active", true,
                    "type", "internal",
                    "attributes", temporary ? Map.of("password_temporary", true) : Map.of()
            );

            Map<?, ?> createdUser = http.post()
                    .uri(baseUrl + "/api/v3/core/users/")
                    .header("Authorization", "Bearer " + apiToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(userPayload)
                    .retrieve()
                    .body(Map.class);

            if (createdUser == null || !createdUser.containsKey("pk")) {
                throw new AuthentikAdminException("Authentik did not return the new user's pk");
            }

            String userId = createdUser.get("pk").toString();

            // Step 2: Set the password
            Map<String, Object> passwordPayload = Map.of("password", password);
            http.post()
                    .uri(baseUrl + "/api/v3/core/users/" + userId + "/set_password/")
                    .header("Authorization", "Bearer " + apiToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(passwordPayload)
                    .retrieve()
                    .toBodilessEntity();

            return userId;

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 && e.getResponseBodyAsString().contains("username")) {
                throw new AuthentikAdminException("a user '" + username + "' already exists in Authentik");
            }
            throw new AuthentikAdminException("creating the Authentik user failed: HTTP " + e.getStatusCode().value() 
                    + " - " + e.getResponseBodyAsString());
        }
    }

    @Override
    public Optional<IdpUser> findUser(String usernameOrEmail) {
        String query = enc(usernameOrEmail.trim());
        try {
            // Try by username first
            List<Map<String, Object>> byUsername = search("username=" + query);
            if (!byUsername.isEmpty()) {
                return user(byUsername);
            }
            // Then try by email
            return user(search("email=" + query));
        } catch (RestClientResponseException e) {
            throw new AuthentikAdminException("looking up the Authentik user failed: HTTP " 
                    + e.getStatusCode().value());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String query) {
        Map<String, Object> response = http.get()
                .uri(baseUrl + "/api/v3/core/users/?" + query)
                .header("Authorization", "Bearer " + apiToken)
                .retrieve()
                .body(Map.class);
        
        if (response == null || !response.containsKey("results")) {
            return List.of();
        }
        
        Object results = response.get("results");
        return results instanceof List ? (List<Map<String, Object>>) results : List.of();
    }

    private static Optional<IdpUser> user(List<Map<String, Object>> users) {
        // An exact match on a unique field yields at most one user; anything else counts as no match
        if (users.size() != 1) {
            return Optional.empty();
        }
        Map<String, Object> u = users.get(0);
        Object pk = u.get("pk");
        if (pk == null) {
            return Optional.empty();
        }
        return Optional.of(new IdpUser(pk.toString(), str(u.get("email")), str(u.get("name"))));
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static class AuthentikAdminException extends RuntimeException {
        public AuthentikAdminException(String message) {
            super(message);
        }
    }
}
