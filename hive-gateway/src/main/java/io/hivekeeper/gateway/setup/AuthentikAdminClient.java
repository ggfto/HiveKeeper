package io.hivekeeper.gateway.setup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authentik Admin API client — only what setup and member management need: create users and look them up. It
 * uses the operator's API token to call the configured Authentik instance and returns the user's pk, which
 * becomes the {@code sub} of the JWTs they will later sign in with.
 *
 * <p><b>That last sentence only holds if the provider's subject mode is "Based on the User's ID"</b>
 * ({@code sub_mode: user_id}). Authentik's default is a salted hash of the id, which would never match the pk
 * we hand to {@code app_user} — the account would be created and then be unable to sign in. {@code
 * deploy/authentik/bootstrap.sh} sets it; a hand-built provider must too.
 *
 * <p>Present under the {@code oidc} profile when {@code hivekeeper.idp=authentik}. The API token is
 * configuration the operator provides for their own Authentik instance; it is never exposed to clients.
 *
 * <p>Authentik API reference: https://docs.goauthentik.io/developer-docs/api
 */
@Component
@Profile("oidc")
@ConditionalOnProperty(name = "hivekeeper.idp", havingValue = "authentik")
public class AuthentikAdminClient implements IdpAdminClient {

    private final RestClient http;
    private final String baseUrl;
    private final String apiToken;

    @Autowired
    public AuthentikAdminClient(
            @Value("${hivekeeper.authentik.base-url:http://localhost:9000}") String baseUrl,
            @Value("${hivekeeper.authentik.api-token}") String apiToken) {
        this(RestClient.builder().requestFactory(authentikCompatibleRequests()), baseUrl, apiToken);
    }

    /**
     * A request factory Authentik's front-end router actually accepts. The JDK's default HTTP client does two
     * things it chokes on, and both fail confusingly rather than loudly:
     *
     * <ul>
     *   <li>it offers an <b>h2c upgrade</b> on plaintext ({@code Upgrade: h2c}), which the router answers with
     *       a bare {@code 400 Invalid HTTP request received};</li>
     *   <li>it streams the body with <b>chunked</b> transfer-encoding, which reaches Django with an EMPTY
     *       body — so Authentik replies {@code 400 {"username":["This field is required."]}} about a field
     *       that was in fact sent.</li>
     * </ul>
     *
     * <p>Pinning HTTP/1.1 drops the upgrade, and buffering the body sets a {@code Content-Length}. The bodies
     * here are a handful of fields, so buffering costs nothing.
     */
    private static ClientHttpRequestFactory authentikCompatibleRequests() {
        HttpClient http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory(http1));
    }

    /**
     * Visible for tests: the only way to point a {@code MockRestServiceServer} at this client, which installs
     * its own request factory on the builder. Spring uses the annotated constructor above —
     * {@code RestClient.Builder} is not a bean here, since the gateway does not pull in
     * {@code spring-boot-restclient}, so it must not become a constructor dependency.
     */
    AuthentikAdminClient(RestClient.Builder http, String baseUrl, String apiToken) {
        this.http = http.build();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiToken = apiToken;
    }

    /**
     * Create a user and return their pk, plus a recovery link when they must choose their own password.
     *
     * <p>Authentik has no equivalent of Keycloak's temporary-password flag, so the two halves differ sharply:
     *
     * <ul>
     *   <li>{@code mustSetOwnPassword} false (first-run admin) — create, then set the given password, which is
     *       permanent. No link.</li>
     *   <li>{@code mustSetOwnPassword} true (a teammate) — create and <b>never set a password at all</b>, then
     *       ask Authentik for a one-time recovery link. Setting an admin-chosen password here and calling it
     *       "temporary" would be a lie: nothing in Authentik would ever force it to be changed, so the admin
     *       would keep a working credential for someone else's account indefinitely.</li>
     * </ul>
     */
    @Override
    public CreatedUser createUser(String username, String email, String password, String displayName,
                                  boolean mustSetOwnPassword) {
        String name = (displayName == null || displayName.isBlank()) ? username : displayName.trim();

        String userId;
        try {
            Map<?, ?> created = http.post()
                    .uri(baseUrl + "/api/v3/core/users/")
                    .header("Authorization", "Bearer " + apiToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "name", name,
                            "email", email == null ? "" : email,
                            "is_active", true,
                            "type", "internal",
                            "path", "users"))
                    .retrieve()
                    .body(Map.class);

            if (created == null || created.get("pk") == null) {
                throw new IdpAdminException("Authentik did not return the new user's pk");
            }
            userId = created.get("pk").toString();
        } catch (RestClientResponseException e) {
            // Authentik answers a duplicate username with 400 and a field-keyed body — verified against a live
            // 2024.8 instance: {"username":["This field must be unique."]}. Matching on the field name alone
            // would misreport any other rejected field as "already exists".
            if (e.getStatusCode().value() == 400 && e.getResponseBodyAsString().contains("must be unique")) {
                throw new IdpAdminException("a user '" + username + "' already exists in Authentik");
            }
            // Authentik answers every rejected field with a keyed body, and the status alone says nothing
            // about which field it disliked — so pass it on, or the operator is left guessing.
            throw new IdpAdminException("creating the Authentik user failed: HTTP "
                    + e.getStatusCode().value() + " " + brief(e.getResponseBodyAsString()));
        }

        // Past this point the account exists. A failure below leaves it there with no way in, which is why the
        // messages say so: the operator must delete it in Authentik (or retry the add) rather than assume
        // nothing happened.
        if (mustSetOwnPassword) {
            return new CreatedUser(userId, recoveryLink(userId, username));
        }
        setPassword(userId, password, username);
        return new CreatedUser(userId, null);
    }

    /** Set a user's password. Only used for the first-run admin, whose password is permanent. */
    private void setPassword(String userId, String password, String username) {
        try {
            http.post()
                    .uri(baseUrl + "/api/v3/core/users/" + userId + "/set_password/")
                    .header("Authorization", "Bearer " + apiToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("password", password))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new IdpAdminException("Authentik created '" + username + "' but rejected their password"
                    + " (HTTP " + e.getStatusCode().value() + "); delete the account in Authentik and retry");
        }
    }

    /**
     * Ask Authentik for the one-time link that lets a new teammate set their own password.
     *
     * <p>This needs a recovery flow set as the active brand's default; without one there is no way for the
     * teammate to get in at all, so we fail loudly with the fix rather than returning a user who can never
     * sign in. {@code deploy/authentik/bootstrap.sh} binds one when the brand has none.
     *
     * <p>Authentik reports that particular misconfiguration as a 400/403 whose body says so — not as a
     * distinct status — so the hint keys off the message rather than the code.
     */
    private String recoveryLink(String userId, String username) {
        try {
            Map<?, ?> body = http.post()
                    .uri(baseUrl + "/api/v3/core/users/" + userId + "/recovery/")
                    .header("Authorization", "Bearer " + apiToken)
                    .retrieve()
                    .body(Map.class);
            Object link = body == null ? null : body.get("link");
            if (link == null || link.toString().isBlank()) {
                throw new IdpAdminException("Authentik created '" + username + "' but returned no recovery"
                        + " link — delete the account in Authentik and retry");
            }
            return link.toString();
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            String hint = detail.toLowerCase().contains("recovery flow")
                    ? "; set a recovery flow as the default on the Authentik brand"
                    : "";
            throw new IdpAdminException("Authentik created '" + username + "' but issued no recovery link"
                    + " (HTTP " + e.getStatusCode().value() + ")" + hint
                    + " — delete the account in Authentik and retry");
        }
    }

    @Override
    public Optional<IdpUser> findUser(String usernameOrEmail) {
        String query = usernameOrEmail.trim();
        try {
            // Authentik's user filters are exact by default (unlike its `search=` parameter, which is a
            // substring match) — a substring match could admit the wrong person to an organization.
            List<Map<String, Object>> byUsername = search("username", query);
            return byUsername.isEmpty() ? user(search("email", query)) : user(byUsername);
        } catch (RestClientResponseException e) {
            throw new IdpAdminException("looking the Authentik user up failed: HTTP " + e.getStatusCode().value());
        }
    }

    /**
     * One exact-match query against the users list. The value goes in as a URI VARIABLE, never pre-encoded
     * into the string: {@code RestClient} encodes the template it is handed, so an already-escaped value comes
     * out double-encoded ({@code b%40x} → {@code b%2540x}) and matches nobody with an e-mail address.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String field, String value) {
        Map<?, ?> response = http.get()
                .uri(baseUrl + "/api/v3/core/users/?" + field + "={value}", value)
                .header("Authorization", "Bearer " + apiToken)
                .retrieve()
                .body(Map.class);
        Object results = response == null ? null : response.get("results");
        return results instanceof List ? (List<Map<String, Object>>) results : List.of();
    }

    private static Optional<IdpUser> user(List<Map<String, Object>> users) {
        // An exact match on a unique field yields at most one user; anything else counts as no match.
        if (users.size() != 1) {
            return Optional.empty();
        }
        Map<String, Object> u = users.get(0);
        Object pk = u.get("pk");
        return pk == null
                ? Optional.empty()
                : Optional.of(new IdpUser(pk.toString(), str(u.get("email")), str(u.get("name"))));
    }

    /** A response body short enough to put in an error message without dumping a page of HTML into a log. */
    private static String brief(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String oneLine = body.strip().replaceAll("\s+", " ");
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
