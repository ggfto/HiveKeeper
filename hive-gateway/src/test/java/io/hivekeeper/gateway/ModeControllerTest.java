package io.hivekeeper.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/mode} is what lets one published console image serve every self-hosted deployment: it learns the
 * deployment's shape — including where to sign in — at runtime instead of at build time.
 */
class ModeControllerTest {

    private static ModeController controller(boolean solo, String issuer, String clientId) {
        return new ModeController(solo, issuer, clientId);
    }

    @Test
    void reportsTheConfiguredSoloFlag() {
        assertFalse(controller(false, "", "hive-gateway").mode().solo(), "default deployment is not solo");
        assertTrue(controller(true, "", "hive-gateway").mode().solo(), "HIVEKEEPER_SOLO=true flips it on");
    }

    @Test
    void handsTheBrowserTheIssuerToSignInAt() {
        ModeController.ModeResponse mode =
                controller(false, "https://id.example.org/realms/hivekeeper", "hive-gateway").mode();

        // The gateway's issuer IS the browser-facing login URL — it is what the tokens it accepts are minted
        // for — so the SPA can take it as the OIDC authority without anything being baked into the bundle.
        assertEquals("https://id.example.org/realms/hivekeeper", mode.oidc().authority());
        assertEquals("hive-gateway", mode.oidc().clientId());
    }

    @Test
    void reportsNoOidcWhenTheGatewayHasNoIdentityProvider() {
        // Without the oidc profile there is no issuer. The console must then show no sign-in button, rather
        // than one that leads nowhere.
        assertNull(controller(false, "", "hive-gateway").mode().oidc());
        assertNull(controller(false, "   ", "hive-gateway").mode().oidc());
    }

    @Test
    void aCustomClientIdIsPassedThrough() {
        // A self-hoster registering the console under their own client id in their own realm.
        assertEquals("hivekeeper-console",
                controller(false, "https://id.example.org/realms/x", "hivekeeper-console").mode().oidc().clientId());
    }
}
