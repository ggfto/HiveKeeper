package io.hivekeeper.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the web app which deployment shape it is talking to, so ONE static bundle serves every deployment.
 *
 * <p>That matters more than it sounds. The console is published as a container image: it is built once, by us,
 * and run by operators at addresses we will never know. Anything it needs but has to learn at build time —
 * an identity provider's URL, above all — would either be baked in wrong for everyone or force every
 * self-hoster to rebuild the image before they can sign in. So the gateway, which already knows all of it,
 * hands it over at runtime:
 *
 * <ul>
 *   <li>{@code solo} — a single-user, single-AP local mode ({@code HIVEKEEPER_SOLO}): no organizations, no
 *       sign-in, every request is the local owner.</li>
 *   <li>{@code oidc} — where to sign in, or {@code null} when this gateway has no identity provider. The
 *       authority is the gateway's own issuer, which is by definition the URL the BROWSER logs in at (it is
 *       what the tokens are minted for), so it is exactly what the SPA needs.</li>
 * </ul>
 *
 * <p>Unauthenticated on purpose — the SPA reads it before it has any identity, and it discloses nothing
 * sensitive: the solo flag, and an issuer and client id that are public in every OIDC deployment on earth.
 * Always present, in every profile.
 */
@RestController
public class ModeController {

    private static final Logger log = LoggerFactory.getLogger(ModeController.class);

    /** @param oidc null when this gateway has no IdP configured — the console then shows no sign-in. */
    public record ModeResponse(boolean solo, Oidc oidc) {
    }

    /** The public half of the OIDC configuration: enough for a browser to start an auth-code + PKCE flow. */
    public record Oidc(String authority, String clientId) {
    }

    private final boolean solo;
    private final Oidc oidc;

    public ModeController(@Value("${hivekeeper.solo:false}") boolean solo,
                          @Value("${hivekeeper.oidc.issuer:}") String issuer,
                          @Value("${hivekeeper.oidc.client-id:hive-gateway}") String clientId) {
        this.solo = solo;
        // No issuer means no oidc profile: this gateway authenticates by X-Tenant-Key (or is solo), and the
        // console must not offer a sign-in button that leads nowhere.
        this.oidc = issuer == null || issuer.isBlank() ? null : new Oidc(issuer.trim(), clientId.trim());
        if (solo) {
            log.warn("SOLO MODE is ON: authentication is disabled and every request is the local owner. "
                    + "Run this only on a trusted machine and bind the gateway to localhost.");
        }
    }

    @GetMapping("/api/mode")
    public ModeResponse mode() {
        return new ModeResponse(solo, oidc);
    }
}
