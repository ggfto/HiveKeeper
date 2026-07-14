package io.hivekeeper.gateway.user;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * The authenticated user's identity and organization memberships — what a client needs to render the org
 * switcher after signing in. Requires a valid bearer JWT (enforced by {@link
 * io.hivekeeper.gateway.security.OidcSecurityConfig}). Only present under the {@code oidc} profile.
 *
 * <p>It does <b>not</b> provision. Somebody who has authenticated but been admitted to nothing gets their
 * identity back and an empty organization list — which is exactly the state a user is in the first time they
 * sign in with GitHub, and the console shows them a "you belong to no organization yet" screen. Writing a row
 * here would mean any account that can reach the identity provider — with GitHub brokered, that is every
 * GitHub account on earth — could grow the table just by signing in.
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

        return users.resolve(jwt)
                .map(user -> new MeResponse(user.userId(), user.email(), user.name(),
                        users.memberships(user.userId())))
                // Authenticated, but admitted to nothing: hand back who they are, with no organizations. The
                // console reads the empty list and tells them to ask an admin for access.
                .orElseGet(() -> new MeResponse(null, email, name, List.of()));
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
