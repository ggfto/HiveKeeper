package io.hivekeeper.gateway;

import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.Grant;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.access.ScopeType;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.gateway.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's first real-database integration test: boots the gateway against a Postgres container,
 * connecting as the RESTRICTED {@code hivekeeper_app} role so Row-Level Security is genuinely exercised. It
 * pins the security boundaries that only a real DB + transactions can prove — the things every unit test
 * (which mocks the JdbcTemplate) is blind to: RLS tenant isolation of grants, the {@code @Transactional}
 * tenant-context coupling, the {@code user_memberships} SECURITY DEFINER cross-org lookup, JIT idempotency,
 * and the composite-FK cross-tenant rejection. Self-skips when no container engine is available.
 */
// RANDOM_PORT (a real embedded servlet container) so the WebSocket message-buffer bean
// (ServletServerContainerFactoryBean, which needs a real jakarta.websocket ServerContainer) can initialize;
// the default MOCK environment has none. The tests below still call the services directly, not over HTTP.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"postgres", "oidc", "demo"})   // 'demo' applies the dev-only seed this IT asserts against
@Testcontainers(disabledWithoutDocker = true)
class GatewayPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hivekeeper")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-app-role.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        // The app connects as the restricted role (RLS applies); Flyway runs as the superuser to own the schema.
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "hivekeeper_app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "postgres");
        r.add("spring.flyway.schemas", () -> "public");
        r.add("hivekeeper.crypto.allow-insecure-dev-key", () -> "true");
        r.add("hivekeeper.oidc.issuer", () -> "https://issuer.test/realms/hk");
        r.add("hivekeeper.oidc.jwk-set-uri", () -> "https://issuer.test/realms/hk/protocol/openid-connect/certs");
    }

    @Autowired
    private AccessService access;
    @Autowired
    private UserService users;
    @Autowired
    private FleetService fleet;
    @Autowired
    private TenantStore tenants;

    // -- AccessService: scoped roles under real RLS + the @Transactional tenant-context coupling -----------

    @Test
    void resolvesScopedRolesAndIsolatesGrantsByTenant() {
        // Resolving anything at all proves @Transactional kept the set_config tenant context alive for the
        // RLS-guarded grant query (the bug a mocked-JdbcTemplate test cannot catch).
        assertEquals(Role.OWNER, access.effectiveRole("acme", "usr-owner", ResourceScope.org()).orElseThrow());
        assertEquals(Role.OPERATOR,
                access.effectiveRole("acme", "usr-op", ResourceScope.site("site-acme-default")).orElseThrow());
        // operator@site does NOT reach the org scope
        assertTrue(access.effectiveRole("acme", "usr-op", ResourceScope.org()).isEmpty());
        // the same owner is only a VIEWER in globex
        assertEquals(Role.VIEWER, access.effectiveRole("globex", "usr-owner", ResourceScope.org()).orElseThrow());

        // RLS isolation: usr-op is not a member of globex, so under the globex context its acme grant is invisible
        assertTrue(access.effectiveRole("globex", "usr-op", ResourceScope.org()).isEmpty());

        List<Grant> opGrants = access.grantsFor("acme", "usr-op");
        assertEquals(1, opGrants.size());
        assertEquals(Role.OPERATOR, opGrants.get(0).role());
        assertEquals(ScopeType.SITE, opGrants.get(0).scopeType());
        assertEquals("site-acme-default", opGrants.get(0).scopeId());
    }

    // -- user_memberships SECURITY DEFINER: spans tenants but only the passed user ------------------------

    @Test
    void membershipsSpanTenantsForOneUserOnly() {
        assertEquals(Set.of("acme", "globex"), tenantIds(users.memberships("usr-owner")));
        assertEquals(Set.of("acme"), tenantIds(users.memberships("usr-op")));

        assertTrue(users.isMember("acme", "usr-op"));
        assertFalse(users.isMember("globex", "usr-op"));
    }

    // -- JIT provisioning idempotency ---------------------------------------------------------------------

    @Test
    void jitProvisioningIsIdempotentForTheSameIdentity() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .issuer("https://issuer.test/realms/hk").subject("brand-new-subject")
                .claim("email", "new@acme.test").build();

        String first = users.resolveOrProvision(jwt);
        String second = users.resolveOrProvision(jwt);
        assertEquals(first, second, "the same (issuer, subject) must resolve to one stable user id");
        assertTrue(users.memberships(first).isEmpty(), "a freshly provisioned user has no organizations yet");
    }

    // -- composite FKs reject cross-tenant references at the DB --------------------------------------------

    @Test
    void crossTenantSiteReferenceIsRejected() {
        String globexSite = fleet.createSite("globex", "GX HQ");

        // acme registering a device pinned to a GLOBEX site must fail the composite (site_id, tenant_id) FK
        assertThrows(DataIntegrityViolationException.class,
                () -> fleet.registerDevice("acme", "SER-CROSS", "AP230", "x", "10.0.0.9", globexSite, null, null));

        // a same-tenant registration succeeds and is listed
        fleet.registerDevice("acme", "SER-OK", "AP230", "lab", "10.0.0.10", "site-acme-default", "lab-agent", "lab-cred");
        assertTrue(fleet.listDevices("acme").stream().anyMatch(d -> "SER-OK".equals(d.serial())));
    }

    // -- credRefForHost: by mgmt_ip, null-filtered, and RLS tenant-isolated -------------------------------

    @Test
    void credRefForHostResolvesByIpFiltersNullsAndIsTenantIsolated() {
        // a device registered WITH a cred_ref resolves by its management IP
        fleet.registerDevice("acme", "SER-CR", "AP230", "cr", "10.7.0.1", "site-acme-default", "lab-agent", "lab-cred");
        assertEquals(Optional.of("lab-cred"), fleet.credRefForHost("acme", "10.7.0.1"));

        // a device with NO cred_ref -> empty (the agent then falls back to its own default)
        fleet.registerDevice("acme", "SER-NOCR", "AP230", "nocr", "10.7.0.2", "site-acme-default", "lab-agent", null);
        assertEquals(Optional.empty(), fleet.credRefForHost("acme", "10.7.0.2"));

        // an unknown host -> empty
        assertEquals(Optional.empty(), fleet.credRefForHost("acme", "203.0.113.254"));

        // RLS: a globex device's cred_ref is invisible from the acme context, and vice versa
        String gxSite = fleet.createSite("globex", "GX cred");
        fleet.registerDevice("globex", "SER-GXCR", "AP230", "gx", "10.7.9.9", gxSite, null, "gx-cred");
        assertEquals(Optional.empty(), fleet.credRefForHost("acme", "10.7.9.9"));
        assertEquals(Optional.of("gx-cred"), fleet.credRefForHost("globex", "10.7.9.9"));
    }

    // -- devicesFor: group / site / org resolution under RLS, carrying cred_ref + groups -------------------

    @Test
    void devicesForResolvesGroupSiteAndOrgUnderRlsCarryingCredRefAndGroups() {
        String devA = fleet.registerDevice("acme", "SER-DA", "AP230", "A", "10.8.0.1",
                "site-acme-default", "lab-agent", "cred-a");
        fleet.registerDevice("acme", "SER-DB", "AP230", "B", "10.8.0.2", "site-acme-default", "lab-agent", null);
        fleet.tagDevice("acme", devA, "grp-acme-default");
        // a globex device must never appear in acme's org-wide resolution
        String gxSite = fleet.createSite("globex", "GX fleet");
        fleet.registerDevice("globex", "SER-GXF", "AP230", "gx", "10.8.9.9", gxSite, null, null);

        // SITE -> contains both acme devices pinned to that site (other tests may add more, so not exact)
        Set<String> bySite = serials(fleet.devicesFor("acme", "site-acme-default", null));
        assertTrue(bySite.containsAll(Set.of("SER-DA", "SER-DB")));

        // GROUP -> only the tagged device, and the row carries its group membership
        List<FleetService.Device> byGroup = fleet.devicesFor("acme", null, "grp-acme-default");
        assertEquals(Set.of("SER-DA"), serials(byGroup));
        assertTrue(byGroup.get(0).groups().contains("grp-acme-default"));
        assertEquals("cred-a", byGroup.get(0).credRef());

        // ORG -> contains acme's devices, never globex's (RLS); the cred_ref is carried on the row
        Set<String> byOrg = serials(fleet.devicesFor("acme", null, null));
        assertTrue(byOrg.containsAll(Set.of("SER-DA", "SER-DB")));
        assertFalse(byOrg.contains("SER-GXF"));
        assertEquals("cred-a", fleet.devicesFor("acme", null, null).stream()
                .filter(d -> "SER-DA".equals(d.serial())).findFirst().orElseThrow().credRef());
    }

    @Test
    void enrollmentIsInsertableByTheAppRoleAndResolvableByToken() {
        // proves the V8 INSERT grant on agent_enrollment (the app role only had SELECT before) end to end:
        // an admin registers an agent and the handshake path can then resolve it by token.
        String token = fleet.createEnrollment("acme", "ci-agent", "site-acme-default");
        assertTrue(token.startsWith("enroll-"));
        AgentEnrollment enrollment = tenants.enrollmentByToken(token).orElseThrow();
        assertEquals("ci-agent", enrollment.agentId());
        assertEquals("acme", enrollment.tenantId());
    }

    private static Set<String> serials(List<FleetService.Device> devices) {
        return devices.stream().map(FleetService.Device::serial).collect(Collectors.toSet());
    }

    private static Set<String> tenantIds(List<UserService.Membership> memberships) {
        return memberships.stream().map(UserService.Membership::tenantId).collect(Collectors.toSet());
    }
}
