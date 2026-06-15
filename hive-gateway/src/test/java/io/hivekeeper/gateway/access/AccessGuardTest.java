package io.hivekeeper.gateway.access;

import io.hivekeeper.gateway.tenant.Tenant;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.gateway.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit-tests the request-auth + authorization logic — the security boundary — with mocked collaborators. */
class AccessGuardTest {

    private final TenantStore tenants = mock(TenantStore.class);
    private final AccessService access = mock(AccessService.class);
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final UserService users = mock(UserService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AccessService> accessProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JwtDecoder> decoderProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserService> userProvider = mock(ObjectProvider.class);

    private AccessGuard guard;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        when(accessProvider.getIfAvailable()).thenReturn(access);
        when(decoderProvider.getIfAvailable()).thenReturn(decoder);
        when(userProvider.getIfAvailable()).thenReturn(users);
        guard = newGuard(true);

        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AccessGuard newGuard(boolean tenantKeyEnabled) {
        return new AccessGuard(tenants, accessProvider, decoderProvider, userProvider, tenantKeyEnabled);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("11111111").issuer("http://localhost/realms/hivekeeper").claim("email", "u@x").build();
    }

    private int statusOf(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (AccessException e) {
            return e.status();
        }
    }

    // -- service principal ------------------------------------------------------

    @Test
    void tenantKeyResolvesToOwner() {
        request.addHeader("X-Tenant-Key", "acme-key");
        when(tenants.tenantByApiKey("acme-key"))
                .thenReturn(Optional.of(new Tenant("acme", "Acme", "acme-key", "owner")));

        Principal p = guard.authenticate();
        assertTrue(p.isService());
        assertEquals(Role.OWNER, p.serviceRole());
        assertEquals("acme", p.tenantId());
        // owner passes any requirement within its tenant WITHOUT consulting the grant resolver
        guard.require(p, Role.OWNER, ResourceScope.org());
        verifyNoInteractions(access);
    }

    @Test
    void tenantKeyWithALowerRoleIsLimitedToThatRole() {
        request.addHeader("X-Tenant-Key", "ops-key");
        when(tenants.tenantByApiKey("ops-key"))
                .thenReturn(Optional.of(new Tenant("acme", "Acme", "ops-key", "operator")));

        Principal p = guard.authenticate();
        assertEquals(Role.OPERATOR, p.serviceRole());
        guard.require(p, Role.OPERATOR, ResourceScope.org());                    // allowed
        assertEquals(403, statusOf(() -> guard.require(p, Role.ADMIN, ResourceScope.org()))); // denied
        verifyNoInteractions(access);   // a service role never consults the grant resolver
    }

    @Test
    void tenantKeyIsIgnoredWhenDisabled() {
        AccessGuard disabled = newGuard(false);
        request.addHeader("X-Tenant-Key", "acme-key");   // a valid key, but the path is off
        assertEquals(401, statusOf(disabled::authenticate));
        verifyNoInteractions(tenants);   // the key was never even looked up
    }

    @Test
    void invalidTenantKeyIs401() {
        request.addHeader("X-Tenant-Key", "nope");
        when(tenants.tenantByApiKey("nope")).thenReturn(Optional.empty());
        assertEquals(401, statusOf(() -> guard.authenticate()));
    }

    // -- user (JWT + X-Org) -----------------------------------------------------

    @Test
    void bearerWithOrgResolvesToAuthorizedUser() {
        request.addHeader("Authorization", "Bearer good");
        request.addHeader("X-Org", "acme");
        when(decoder.decode("good")).thenReturn(jwt());
        when(users.resolveOrProvision(any())).thenReturn("usr-1");
        when(users.isMember("acme", "usr-1")).thenReturn(true);

        Principal p = guard.authenticate();
        assertFalse(p.isService());
        assertEquals("acme", p.tenantId());
        assertEquals("usr-1", p.userId());
    }

    @Test
    void bearerWithoutOrgIs400() {
        request.addHeader("Authorization", "Bearer good");
        when(decoder.decode("good")).thenReturn(jwt());
        assertEquals(400, statusOf(() -> guard.authenticate()));
    }

    @Test
    void bearerForNonMemberOrgIs403() {
        request.addHeader("Authorization", "Bearer good");
        request.addHeader("X-Org", "globex");
        when(decoder.decode("good")).thenReturn(jwt());
        when(users.resolveOrProvision(any())).thenReturn("usr-1");
        when(users.isMember("globex", "usr-1")).thenReturn(false);
        assertEquals(403, statusOf(() -> guard.authenticate()));
    }

    @Test
    void invalidBearerTokenIs401() {
        request.addHeader("Authorization", "Bearer bad");
        request.addHeader("X-Org", "acme");
        when(decoder.decode("bad")).thenThrow(new JwtException("rejected"));
        assertEquals(401, statusOf(() -> guard.authenticate()));
    }

    @Test
    void noCredentialsIs401() {
        assertEquals(401, statusOf(() -> guard.authenticate()));
    }

    @Test
    void tenantKeyTakesPrecedenceOverBearer() {
        request.addHeader("X-Tenant-Key", "acme-key");
        request.addHeader("Authorization", "Bearer good");
        when(tenants.tenantByApiKey("acme-key"))
                .thenReturn(Optional.of(new Tenant("acme", "Acme", "acme-key", "owner")));

        Principal p = guard.authenticate();
        assertTrue(p.isService(), "an explicit service key authenticates as the service principal");
    }

    // -- require() --------------------------------------------------------------

    @Test
    void requireForwardsTheExactScopeAndDeniesWithoutTheRole() {
        Principal user = Principal.user("acme", "usr-1");
        ResourceScope scope = ResourceScope.site("site-a");
        when(access.allows(eq("acme"), eq("usr-1"), eq(Role.ADMIN), eq(scope))).thenReturn(false);
        when(access.allows(eq("acme"), eq("usr-1"), eq(Role.VIEWER), eq(scope))).thenReturn(true);

        assertEquals(403, statusOf(() -> guard.require(user, Role.ADMIN, scope)));
        guard.require(user, Role.VIEWER, scope);   // allowed -> no throw

        // pin that require() forwards the EXACT scope it was handed (not org/empty/another)
        ArgumentCaptor<ResourceScope> captor = ArgumentCaptor.forClass(ResourceScope.class);
        verify(access, atLeastOnce()).allows(eq("acme"), eq("usr-1"), any(), captor.capture());
        assertEquals(scope, captor.getValue());
    }
}
