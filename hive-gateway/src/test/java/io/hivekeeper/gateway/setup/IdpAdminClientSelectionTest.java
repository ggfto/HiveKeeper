package io.hivekeeper.gateway.setup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exactly one {@link IdpAdminClient} must exist, whatever the operator configured.
 *
 * <p>This exists because the first cut expressed the provider as a second Spring profile
 * ({@code oidc-authentik} alongside {@code oidc}). Every other OIDC bean — setup, members, the resource-server
 * config — requires {@code oidc}, so the documented profile list activated BOTH clients and the context died
 * at startup with "expected single matching bean but found 2". No test noticed, because none of them ever
 * built a context with the profile combination the compose file and the README told people to use.
 *
 * <p>So the cases below are the deployment matrix, not an abstraction exercise: the two profile lists that
 * ship, plus the upgrade case of an existing deployment that sets no property at all.
 */
class IdpAdminClientSelectionTest {

    /** Stands in for SetupService/MemberService: one constructor parameter, no qualifier. */
    static class NeedsOneIdp {
        final IdpAdminClient idp;

        NeedsOneIdp(IdpAdminClient idp) {
            this.idp = idp;
        }
    }

    private void withProfilesAndProperties(String[] profiles, String[] properties,
                                           Consumer<AssertableApplicationContext> assertions) {
        new ApplicationContextRunner()
                .withUserConfiguration(KeycloakAdminClient.class, AuthentikAdminClient.class, NeedsOneIdp.class)
                .withPropertyValues(properties)
                .withInitializer((ConfigurableApplicationContext ctx) ->
                        ctx.getEnvironment().setActiveProfiles(profiles))
                .run(assertions::accept);
    }

    @Test
    void theDefaultOidcDeploymentGetsKeycloakAndNothingElse() {
        // An existing deployment sets no hivekeeper.idp at all; it must keep the client it has always had.
        withProfilesAndProperties(new String[]{"oidc"}, new String[]{}, ctx -> {
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
            assertEquals(1, ctx.getBeansOfType(IdpAdminClient.class).size());
            assertInstanceOf(KeycloakAdminClient.class, ctx.getBean(NeedsOneIdp.class).idp);
        });
    }

    @Test
    void theDocumentedAuthentikProfileListGetsAuthentikAndNothingElse() {
        // Exactly what docker-compose.authentik.yml sets: the oidc profile PLUS the authentik overlay, whose
        // properties file is what supplies hivekeeper.idp=authentik.
        withProfilesAndProperties(
                new String[]{"oidc", "oidc-authentik"},
                new String[]{"hivekeeper.idp=authentik", "hivekeeper.authentik.api-token=t"},
                ctx -> {
                    assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
                    assertEquals(1, ctx.getBeansOfType(IdpAdminClient.class).size());
                    assertInstanceOf(AuthentikAdminClient.class, ctx.getBean(NeedsOneIdp.class).idp);
                });
    }

    @Test
    void anExplicitKeycloakSelectionKeepsAuthentikOut() {
        withProfilesAndProperties(new String[]{"oidc"}, new String[]{"hivekeeper.idp=keycloak"}, ctx -> {
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
            assertInstanceOf(KeycloakAdminClient.class, ctx.getBean(NeedsOneIdp.class).idp);
        });
    }

    @Test
    void aTypoInTheProviderNameFailsAtStartupRatherThanSilentlyPickingKeycloak() {
        // Landing on the wrong IdP would provision users somewhere nobody is looking; no bean at all stops the
        // gateway with a message instead.
        withProfilesAndProperties(new String[]{"oidc"}, new String[]{"hivekeeper.idp=authentic"}, ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertTrue(ctx.getStartupFailure().getMessage().contains("IdpAdminClient"),
                    ctx.getStartupFailure().getMessage());
        });
    }
}
