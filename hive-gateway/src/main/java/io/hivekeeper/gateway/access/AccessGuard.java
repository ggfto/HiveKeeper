package io.hivekeeper.gateway.access;

import io.hivekeeper.gateway.tenant.Tenant;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.gateway.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Optional;

/**
 * Resolves the calling principal for a request and authorizes actions against it — the single enforcement
 * seam the controllers call.
 *
 * <p>Two ways to authenticate, checked in this order:
 * <ol>
 *   <li><b>Service principal</b> — an {@code X-Tenant-Key} maps to a tenant and acts as owner (full rights),
 *       for automation/CI and backward compatibility. Checked first, so existing callers are unaffected.</li>
 *   <li><b>User</b> — a bearer JWT plus an {@code X-Org} header naming the active organization. The token is
 *       decoded here (the bearer filter only guards {@code /api/me}); the user is provisioned just-in-time,
 *       their membership in {@code X-Org} is verified, and {@link #require} then checks their scoped role.</li>
 * </ol>
 * Only present under the {@code postgres} profile; the JWT path additionally needs the {@code oidc} profile
 * (its decoder + user service), which is why those are optional.
 */
@Component
@Profile("postgres")
public class AccessGuard {

    private final TenantStore tenants;
    private final AccessService access;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<UserService> users;

    public AccessGuard(TenantStore tenants, AccessService access,
                       ObjectProvider<JwtDecoder> jwtDecoder, ObjectProvider<UserService> users) {
        this.tenants = tenants;
        this.access = access;
        this.jwtDecoder = jwtDecoder;
        this.users = users;
    }

    /** Resolves the caller, or throws {@link AccessException} (401/400/403). */
    public Principal authenticate() {
        HttpServletRequest req = currentRequest();

        String apiKey = req.getHeader("X-Tenant-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return tenants.tenantByApiKey(apiKey).map(Tenant::tenantId).map(Principal::owner)
                    .orElseThrow(() -> new AccessException(401, "unauthorized", "invalid X-Tenant-Key"));
        }

        String authorization = req.getHeader("Authorization");
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        UserService userService = users.getIfAvailable();
        if (authorization != null && authorization.startsWith("Bearer ") && decoder != null && userService != null) {
            Jwt jwt;
            try {
                jwt = decoder.decode(authorization.substring("Bearer ".length()));
            } catch (JwtException e) {
                throw new AccessException(401, "invalid_token", "bearer token rejected");
            }
            String org = req.getHeader("X-Org");
            if (org == null || org.isBlank()) {
                throw new AccessException(400, "x_org_required", "X-Org header names the active organization");
            }
            String userId = userService.resolveOrProvision(jwt);
            if (!userService.isMember(org, userId)) {
                throw new AccessException(403, "not_a_member", "not a member of organization " + org);
            }
            return Principal.user(org, userId);
        }

        throw new AccessException(401, "unauthorized", "provide an X-Tenant-Key or a bearer token + X-Org");
    }

    /** Authorizes the principal for {@code required} on {@code scope}, or throws 403. A service owner passes
     *  unconditionally within its tenant; a user is checked against their scoped grants. */
    public void require(Principal principal, Role required, ResourceScope scope) {
        if (principal.owner()) {
            return;
        }
        if (!access.allows(principal.tenantId(), principal.userId(), required, scope)) {
            throw new AccessException(403, "forbidden", "requires " + required + " on this resource");
        }
    }

    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest();
    }
}
