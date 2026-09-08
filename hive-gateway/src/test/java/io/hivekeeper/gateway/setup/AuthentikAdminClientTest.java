package io.hivekeeper.gateway.setup;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the HTTP conversation with Authentik: the exact endpoints, the bearer header, and — the part that is
 * easy to get wrong and impossible to see from the outside — that a teammate is created with NO password and
 * a recovery link, while the first-run admin gets a password and no link.
 *
 * <p>The client is built from an injected {@link RestClient.Builder} precisely so this can bind a
 * {@link MockRestServiceServer} to it; a client that called {@code RestClient.create()} internally could only
 * be tested against a live Authentik.
 */
class AuthentikAdminClientTest {

    private static final String BASE = "http://authentik:9000";
    private static final String TOKEN = "test-token-123";
    private static final String USERS = BASE + "/api/v3/core/users/";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AuthentikAdminClient client = new AuthentikAdminClient(builder, BASE, TOKEN);

    // --- creating the first-run admin -----------------------------------------------------------------

    @Test
    void createAdminCreatesTheUserThenSetsAPermanentPasswordAndReturnsThePk() {
        server.expect(requestTo(USERS)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.username").value("johndoe"))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john@example.com"))
                .andExpect(jsonPath("$.type").value("internal"))
                .andRespond(withSuccess("{\"pk\":42,\"username\":\"johndoe\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "42/set_password/")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.password").value("secret123"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        // createAdmin is the interface's default method: it must delegate with mustSetOwnPassword = false.
        String subject = client.createAdmin("johndoe", "john@example.com", "secret123", "John Doe");

        assertEquals("42", subject);
        server.verify();
    }

    @Test
    void displayNameFallsBackToTheUsernameWhenBlank() {
        server.expect(requestTo(USERS)).andExpect(jsonPath("$.name").value("johndoe"))
                .andRespond(withSuccess("{\"pk\":7}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "7/set_password/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        assertEquals("7", client.createAdmin("johndoe", "j@x", "pw", "   "));
        server.verify();
    }

    // --- creating a teammate --------------------------------------------------------------------------

    @Test
    void createTeammateNeverSetsAPasswordAndReturnsTheRecoveryLink() {
        server.expect(requestTo(USERS)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"pk\":99}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "99/recovery/")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"link\":\"https://authentik/if/flow/recovery/?token=abc\"}",
                        MediaType.APPLICATION_JSON));

        IdpAdminClient.CreatedUser created =
                client.createUser("bob", "b@x", "ignored-throwaway", "Bob", true);

        assertEquals("99", created.subject());
        assertEquals("https://authentik/if/flow/recovery/?token=abc", created.recoveryLink());
        // The decisive assertion: MockRestServiceServer fails on any unexpected request, so a set_password
        // call here would fail the test. The admin's throwaway password never reaches Authentik.
        server.verify();
    }

    @Test
    void createTeammateFailsLoudlyWhenNoRecoveryFlowIsConfigured() {
        server.expect(requestTo(USERS)).andRespond(withSuccess("{\"pk\":99}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "99/recovery/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createUser("bob", "b@x", "pw", "Bob", true));

        // A user who can never sign in is worse than a failed add, so the message must name the fix.
        assertTrue(e.getMessage().contains("recovery flow"), e.getMessage());
        assertTrue(e.getMessage().contains("bob"), e.getMessage());
    }

    @Test
    void createTeammateRejectsABlankRecoveryLink() {
        server.expect(requestTo(USERS)).andRespond(withSuccess("{\"pk\":99}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "99/recovery/"))
                .andRespond(withSuccess("{\"link\":\"\"}", MediaType.APPLICATION_JSON));

        assertThrows(IdpAdminException.class, () -> client.createUser("bob", "b@x", "pw", "Bob", true));
    }

    // --- failures -------------------------------------------------------------------------------------

    @Test
    void duplicateUsernameBecomesAnIdpAdminException() {
        server.expect(requestTo(USERS)).andRespond(withBadRequest()
                .body("{\"username\":[\"User with this Username already exists.\"]}")
                .contentType(MediaType.APPLICATION_JSON));

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin("johndoe", "j@x", "pw", "John"));

        // The type matters as much as the text: MemberController maps THIS type to 409, so an
        // Authentik-specific exception here would surface as an unmapped 500.
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        assertTrue(e.getMessage().contains("johndoe"), e.getMessage());
    }

    @Test
    void anUnexpectedCreateFailureBecomesAnIdpAdminException() {
        server.expect(requestTo(USERS))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin("johndoe", "j@x", "pw", "John"));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void aRejectedPasswordSaysTheAccountWasAlreadyCreated() {
        server.expect(requestTo(USERS)).andRespond(withSuccess("{\"pk\":5}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "5/set_password/")).andRespond(withBadRequest());

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin("johndoe", "j@x", "weak", "John"));

        // The account is left behind in Authentik; the operator has to know that to clean up.
        assertTrue(e.getMessage().contains("delete the account"), e.getMessage());
    }

    @Test
    void aResponseWithoutAPkBecomesAnIdpAdminException() {
        server.expect(requestTo(USERS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(IdpAdminException.class, () -> client.createAdmin("johndoe", "j@x", "pw", "John"));
    }

    // --- looking users up -----------------------------------------------------------------------------

    @Test
    void findUserMatchesOnUsernameWithoutFallingBackToEmail() {
        server.expect(requestTo(USERS + "?username=bob")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(
                        "{\"results\":[{\"pk\":3,\"email\":\"b@x\",\"name\":\"Bob\"}]}",
                        MediaType.APPLICATION_JSON));

        Optional<IdpAdminClient.IdpUser> found = client.findUser("bob");

        assertTrue(found.isPresent());
        assertEquals("3", found.get().subject());
        assertEquals("b@x", found.get().email());
        assertEquals("Bob", found.get().name());
        server.verify();   // no second (email) request was made
    }

    @Test
    void findUserFallsBackToEmailWhenTheUsernameDoesNotMatch() {
        server.expect(requestTo(USERS + "?username=b%40x.com"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?email=b%40x.com")).andRespond(withSuccess(
                "{\"results\":[{\"pk\":4,\"email\":\"b@x.com\",\"name\":\"Bob\"}]}",
                MediaType.APPLICATION_JSON));

        assertEquals("4", client.findUser("  b@x.com  ").orElseThrow().subject());
        server.verify();   // and the query was URL-encoded and trimmed
    }

    @Test
    void findUserReturnsEmptyWhenNeitherMatches() {
        server.expect(requestTo(USERS + "?username=nobody"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?email=nobody"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(client.findUser("nobody").isEmpty());
    }

    @Test
    void findUserRefusesToGuessBetweenSeveralMatches() {
        // Admitting the wrong person to an organization is worse than failing to find them.
        server.expect(requestTo(USERS + "?username=bob")).andRespond(withSuccess(
                "{\"results\":[{\"pk\":1},{\"pk\":2}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "?email=bob"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(client.findUser("bob").isEmpty());
    }

    @Test
    void findUserReportsAFailedLookupRatherThanReturningEmpty() {
        server.expect(requestTo(USERS + "?username=bob"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        IdpAdminException e = assertThrows(IdpAdminException.class, () -> client.findUser("bob"));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
    }

    @Test
    void aUserWithoutAnEmailOrNameYieldsNullsRatherThanBlanks() {
        server.expect(requestTo(USERS + "?username=bob")).andRespond(withSuccess(
                "{\"results\":[{\"pk\":3,\"email\":\"\",\"name\":\"  \"}]}", MediaType.APPLICATION_JSON));

        IdpAdminClient.IdpUser u = client.findUser("bob").orElseThrow();
        assertNull(u.email());
        assertNull(u.name());
    }

    // --- configuration --------------------------------------------------------------------------------

    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        AuthentikAdminClient withSlash = new AuthentikAdminClient(b, BASE + "/", TOKEN);

        s.expect(requestTo(USERS)).andRespond(withSuccess("{\"pk\":1}", MediaType.APPLICATION_JSON));
        s.expect(requestTo(USERS + "1/set_password/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        withSlash.createAdmin("johndoe", "j@x", "pw", "John");
        s.verify();
    }
}
