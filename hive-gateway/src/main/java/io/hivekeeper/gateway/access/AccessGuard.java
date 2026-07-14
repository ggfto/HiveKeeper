package io.hivekeeper.gateway.access;

import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.gateway.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
 * Always present, so it works in every profile; the JWT/role machinery (decoder, user service, resolver) is
 * optional and only wired under {@code oidc}+{@code postgres}. Without it, only the {@code X-Tenant-Key} owner
 * path is available — which is exactly the pre-enforcement behavior.
 */
@Component
public class AccessGuard {

    /** Request attribute holding the resolved {@link Principal}, so one request authenticates exactly once. */
    private static final String PRINCIPAL_ATTRIBUTE = AccessGuard.class.getName() + ".principal";

    private final TenantStore tenants;
    private final ObjectProvider<AccessService> access;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<UserService> users;
    private final boolean tenantKeyEnabled;
    private final boolean solo;
    private final String soloTenant;

    public AccessGuard(TenantStore tenants, ObjectProvider<AccessService> access,
                       ObjectProvider<JwtDecoder> jwtDecoder, ObjectProvider<UserService> users,
                       @Value("${hivekeeper.tenantkey.enabled:true}") boolean tenantKeyEnabled,
                       @Value("${hivekeeper.solo:false}") boolean solo,
                       @Value("${hivekeeper.solo.tenant:local}") String soloTenant) {
        this.tenants = tenants;
        this.access = access;
        this.jwtDecoder = jwtDecoder;
        this.users = users;
        this.tenantKeyEnabled = tenantKeyEnabled;
        this.solo = solo;
        this.soloTenant = soloTenant;
    }

    /**
     * Resolves the caller, or throws {@link AccessException} (401/400/403).
     *
     * <p>Memoized per request: {@link AuthenticationBackstop} authenticates every non-public request before the
     * handler runs, and the handler then calls this again (as does each nested authorization check), so without
     * a cache one request would decode and verify the same JWT several times.
     */
    public Principal authenticate() {
        HttpServletRequest req = currentRequest();
        if (req.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof Principal cached) {
            return cached;
        }
        Principal principal = resolve(req);
        req.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        return principal;
    }

    private Principal resolve(HttpServletRequest req) {
        // The X-Tenant-Key path can be turned off entirely (hivekeeper.tenantkey.enabled=false) so a hardened
        // OIDC deployment accepts only user JWTs; when off, a key is ignored and the caller falls through to JWT.
        String apiKey = req.getHeader("X-Tenant-Key");
        if (tenantKeyEnabled && apiKey != null && !apiKey.isBlank()) {
            // A key grants its tenant's configured role (usually OWNER, but a key can be issued a lower one).
            return tenants.tenantByApiKey(apiKey)
                    .map(t -> Principal.service(t.tenantId(), Role.of(t.operatorRole())))
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
            // A token we have never seen is a person we have never admitted — the same answer as a non-member,
            // and no row written. This used to provision on sight, which with a brokered identity provider
            // would let any GitHub account on earth write to app_user before being refused.
            String userId = userService.resolve(jwt)
                    .map(UserService.AppUser::userId)
                    .orElseThrow(() -> new AccessException(403, "not_a_member",
                            "not a member of organization " + org));
            if (!userService.isMember(org, userId)) {
                throw new AccessException(403, "not_a_member", "not a member of organization " + org);
            }
            return Principal.user(org, userId);
        }

        // Solo (single-user, single-AP local) mode: there is no identity provider and no keys — the person at
        // the machine IS the owner. Only reached when no credentials were supplied, so an explicit key/bearer
        // still takes its normal path. Enabled by HIVEKEEPER_SOLO; never on by default.
        if (solo) {
            return Principal.owner(soloTenant);
        }

        throw new AccessException(401, "unauthorized", "provide an X-Tenant-Key or a bearer token + X-Org");
    }

    /** Whether the principal has at least {@code required} on {@code scope} (a service owner always does
     *  within its tenant). Non-throwing — used to filter lists. */
    public boolean allows(Principal principal, Role required, ResourceScope scope) {
        if (principal.isService()) {
            // A service principal carries one fixed, tenant-wide role; scope does not narrow it.
            return principal.serviceRole().atLeast(required);
        }
        AccessService resolver = access.getIfAvailable();
        return resolver != null && resolver.allows(principal.tenantId(), principal.userId(), required, scope);
    }

    /** Authorizes the principal for {@code required} on {@code scope}, or throws 403. */
    public void require(Principal principal, Role required, ResourceScope scope) {
        if (!allows(principal, required, scope)) {
            throw new AccessException(403, "forbidden", "requires " + required + " on this resource");
        }
    }

    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest();
    }
}
