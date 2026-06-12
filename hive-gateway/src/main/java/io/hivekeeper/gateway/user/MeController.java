package io.hivekeeper.gateway.user;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * The authenticated user's identity and organization memberships — the data a client needs to render the
 * org switcher after OIDC login. Requires a valid bearer JWT (enforced by {@link
 * io.hivekeeper.gateway.security.OidcSecurityConfig}); the user is provisioned just-in-time from the token.
 * Only present under the {@code oidc} profile.
 */
@RestController
@Profile("oidc")
public class MeController {

    public record MeResponse(String userId, String email, String name, List<UserService.Membership> organizations) {
    }

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        String issuer = String.valueOf(jwt.getIssuer());
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = firstNonBlank(jwt.getClaimAsString("name"),
                jwt.getClaimAsString("preferred_username"), email);

        UserService.AppUser user = users.provision(issuer, subject, email, name);
        return new MeResponse(user.userId(), user.email(), user.name(), users.memberships(user.userId()));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
