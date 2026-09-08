package io.hivekeeper.gateway.setup;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the HTTP conversation with Keycloak — the half of the {@link IdpAdminClient} contract that had no test
 * at all. It exists mainly to hold two things still: that a teammate's password is marked temporary AND
 * carries an {@code UPDATE_PASSWORD} action (that pair is the whole reason Keycloak needs no recovery link),
 * and that a lookup by e-mail is encoded exactly once.
 */
class KeycloakAdminClientTest {

    private static final String BASE = "http://keycloak:8081";
    private static final String TOKEN_URL = BASE + "/realms/master/protocol/openid-connect/token";
    private static final String USERS = BASE + "/admin/realms/hivekeeper/users";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KeycloakAdminClient client = new KeycloakAdminClient(
            builder, BASE, "hivekeeper", "master", "admin-cli", "admin", "admin");

    private void expectAdminToken() {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"adm-tok\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void createAdminSetsAPermanentPasswordWithNoPendingActionAndNoLink() {
        expectAdminToken();
        HttpHeaders location = new HttpHeaders();
        location.add("Location", USERS + "/kc-123");
        server.expect(requestTo(USERS)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.firstName").value("Olivia"))
                .andExpect(jsonPath("$.lastName").value("Owner"))
                .andExpect(jsonPath("$.requiredActions").isEmpty())
                .andExpect(jsonPath("$.credentials[0].temporary").value(false))
                .andRespond(withStatus(HttpStatus.CREATED).headers(location));

        String subject = client.createAdmin("admin", "a@x", "pw", "Olivia Owner");

        assertEquals("kc-123", subject);
        server.verify();
    }

    @Test
    void createTeammateMarksThePasswordTemporaryAndPinsAnUpdatePasswordAction() {
        expectAdminToken();
        HttpHeaders location = new HttpHeaders();
        location.add("Location", USERS + "/kc-bob");
        server.expect(requestTo(USERS))
                .andExpect(jsonPath("$.requiredActions[0]").value("UPDATE_PASSWORD"))
                .andExpect(jsonPath("$.credentials[0].temporary").value(true))
                .andRespond(withStatus(HttpStatus.CREATED).headers(location));

        IdpAdminClient.CreatedUser created = client.createUser("bob", "b@x", "tmp", "Bob Builder", true);

        assertEquals("kc-bob", created.subject());
        // Keycloak forces the change itself, so there is nothing for the admin to pass on. Returning a link
        // here would mean the console showed one under a provider that has none.
        assertNull(created.recoveryLink());
        server.verify();
    }

    @Test
    void aSingleWordDisplayNameFillsBothNameFields() {
        expectAdminToken();
        HttpHeaders location = new HttpHeaders();
        location.add("Location", USERS + "/kc-1");
        // Keycloak refuses to consider a user "fully set up" without both names, so neither may be blank.
        server.expect(requestTo(USERS))
                .andExpect(jsonPath("$.firstName").value("Bob"))
                .andExpect(jsonPath("$.lastName").value("Bob"))
                .andRespond(withStatus(HttpStatus.CREATED).headers(location));

        assertEquals("kc-1", client.createAdmin("bob", "b@x", "pw", "Bob"));
    }

    @Test
    void aDuplicateUsernameBecomesAnIdpAdminException() {
        expectAdminToken();
        server.expect(requestTo(USERS)).andRespond(withStatus(HttpStatus.CONFLICT));

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin("bob", "b@x", "pw", "Bob"));
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
    }

    @Test
    void findUserEncodesAnEmailExactlyOnce() {
        expectAdminToken();
        // The bug this pins: pre-escaping the value and then handing RestClient a template turned b%40x.com
        // into b%2540x.com, so a lookup by e-mail address silently matched nobody.
        server.expect(requestTo(USERS + "?username=b%40x.com&exact=true"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?email=b%40x.com&exact=true")).andRespond(withSuccess(
                "[{\"id\":\"kc-9\",\"email\":\"b@x.com\",\"firstName\":\"Bob\",\"lastName\":\"Builder\"}]",
                MediaType.APPLICATION_JSON));

        IdpAdminClient.IdpUser found = client.findUser("  b@x.com  ").orElseThrow();

        assertEquals("kc-9", found.subject());
        assertEquals("b@x.com", found.email());
        server.verify();
    }

    @Test
    void findUserRefusesToGuessBetweenSeveralMatches() {
        expectAdminToken();
        server.expect(requestTo(USERS + "?username=bob&exact=true")).andRespond(
                withSuccess("[{\"id\":\"kc-1\"},{\"id\":\"kc-2\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?email=bob&exact=true"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertTrue(client.findUser("bob").isEmpty());
    }

    @Test
    void badAdminCredentialsSayWhichSettingsToCheck() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin("bob", "b@x", "pw", "Bob"));
        assertTrue(e.getMessage().contains("admin-username/password"), e.getMessage());
    }
}
