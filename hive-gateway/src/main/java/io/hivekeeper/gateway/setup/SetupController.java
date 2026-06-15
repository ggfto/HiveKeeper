package io.hivekeeper.gateway.setup;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * First-run setup API — intentionally UNAUTHENTICATED (no operator exists yet) but protected by the setup
 * token and the "only while uninitialized" lock in {@link SetupService}. {@code GET /api/setup/status} lets the
 * web app decide whether to show the wizard; {@code POST /api/setup} creates the first org + admin. Only present
 * under the {@code oidc} profile.
 */
@RestController
@Profile("oidc")
public class SetupController {

    /** {@code setupToken} is the one-time token printed on the server console at first boot. {@code name} is the
     *  admin's display name (optional; defaults to the username). */
    public record SetupRequest(String setupToken, String orgName, String username, String password, String email,
                               String name) {
    }

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/api/setup/status")
    public Map<String, Object> status() {
        return Map.of("initialized", setup.isInitialized());
    }

    @PostMapping("/api/setup")
    public ResponseEntity<Map<String, Object>> doSetup(@RequestBody SetupRequest req) {
        try {
            SetupService.SetupResult result = setup.setup(
                    req.setupToken(), req.orgName(), req.username(), req.password(), req.email(), req.name());
            return ResponseEntity.ok(Map.of("tenantId", result.tenantId()));
        } catch (SetupException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (KeycloakAdminException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
