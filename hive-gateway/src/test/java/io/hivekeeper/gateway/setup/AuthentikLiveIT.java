package io.hivekeeper.gateway.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link AuthentikAdminClient} against a REAL Authentik. Opt-in: set {@code AUTHENTIK_IT_URL} and
 * {@code AUTHENTIK_IT_TOKEN} and it runs, otherwise it is skipped, so it never gates CI.
 *
 * <pre>
 *   docker compose -f docker-compose.yml -f docker-compose.postgres.yml -f docker-compose.authentik.yml \
 *     up -d authentik-db authentik-redis authentik-server authentik-worker
 *   docker compose ... run --rm authentik-init
 *   AUTHENTIK_IT_URL=http://localhost:9000 AUTHENTIK_IT_TOKEN=$TOKEN ./gradlew :hive-gateway:test \
 *     --tests '*AuthentikLiveIT*'
 * </pre>
 *
 * <p>The mocked unit tests pin the conversation we <em>believe</em> Authentik has; this pins the one it
 * actually has. It is what caught the API's real shapes — that a duplicate is a 400 keyed by field rather
 * than a 409, that {@code pk} comes back as a number, and that the user filters are exact.
 */
@EnabledIfEnvironmentVariable(named = "AUTHENTIK_IT_URL", matches = ".+")
class AuthentikLiveIT {

    // The PRODUCTION constructor on purpose: it is the one that installs the request factory Authentik's
    // router accepts, and getting that wrong is invisible to the mocked tests.
    private final AuthentikAdminClient client = new AuthentikAdminClient(
            System.getenv("AUTHENTIK_IT_URL"), System.getenv("AUTHENTIK_IT_TOKEN"));

    /** A fresh username per run, so re-running does not trip over its own leftovers. */
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsAnAdminWithAWorkingPasswordAndFindsThemAgain() {
        String username = unique("it-admin");

        String subject = client.createAdmin(username, username + "@example.test", "Str0ng-Passw0rd!x", "It Admin");

        assertNotNull(subject);
        // The subject must be the pk, because that is what the provider's sub_mode=user_id will put in the
        // token. A non-numeric value here means the client returned something else entirely.
        assertTrue(subject.matches("\\d+"), "subject should be the numeric pk, was: " + subject);

        Optional<IdpAdminClient.IdpUser> found = client.findUser(username);
        assertTrue(found.isPresent());
        assertEquals(subject, found.get().subject());
        assertEquals("It Admin", found.get().name());
    }

    @Test
    void findsAUserByEmailAddress() {
        // The double-encoding bug lived exactly here: '@' became %2540 and matched nobody.
        String username = unique("it-email");
        String email = username + "@example.test";
        client.createAdmin(username, email, "Str0ng-Passw0rd!x", "It Email");

        assertEquals(client.findUser(username).orElseThrow().subject(),
                client.findUser(email).orElseThrow().subject());
    }

    @Test
    void returnsEmptyForSomebodyWhoDoesNotExist() {
        assertTrue(client.findUser(unique("it-nobody")).isEmpty());
    }

    @Test
    void createsATeammateWithNoPasswordAndARealRecoveryLink() {
        String username = unique("it-mate");

        IdpAdminClient.CreatedUser created =
                client.createUser(username, username + "@example.test", "never-sent", "It Mate", true);

        assertTrue(created.subject().matches("\\d+"));
        assertNotNull(created.recoveryLink(), "Authentik must issue a recovery link for a teammate");
        assertTrue(created.recoveryLink().contains("flow_token="), created.recoveryLink());
    }

    @Test
    void rejectsADuplicateUsernameAsAnIdpAdminException() {
        String username = unique("it-dup");
        client.createAdmin(username, username + "@example.test", "Str0ng-Passw0rd!x", "It Dup");

        IdpAdminException e = assertThrows(IdpAdminException.class,
                () -> client.createAdmin(username, "other@example.test", "Str0ng-Passw0rd!x", "It Dup"));
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
    }

    @Test
    void aBadTokenFailsTheLookupInsteadOfReportingNobody() {
        AuthentikAdminClient bad =
                new AuthentikAdminClient(System.getenv("AUTHENTIK_IT_URL"), "not-a-real-token");

        // Returning Optional.empty() here would silently tell an admin "no such account" on a broken token.
        assertThrows(IdpAdminException.class, () -> bad.findUser("whoever"));
    }

    @Test
    void theFirstRunAdminGetsNoRecoveryLink() {
        String username = unique("it-nolink");
        IdpAdminClient.CreatedUser created =
                client.createUser(username, username + "@example.test", "Str0ng-Passw0rd!x", "It NoLink", false);
        assertNull(created.recoveryLink());
    }
}
