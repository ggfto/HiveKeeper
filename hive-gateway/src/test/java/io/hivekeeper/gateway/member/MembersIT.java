package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.KeycloakAdminClient;
import io.hivekeeper.gateway.setup.SetupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Member management end-to-end against a real Postgres — the RESTRICTED app role + RLS that unit tests can't
 * exercise. Bootstraps a first org + owner, then adds a teammate, lists, re-roles, and removes them, all
 * through the live tenant-context writes; effective roles are resolved through the real grant resolver.
 * Keycloak is mocked (its Admin API shape is proven separately). Self-skips without a container engine.
 *
 * <p>Uses RANDOM_PORT (not the default MOCK env) because the full context wires the agent WebSocket container,
 * which needs a real servlet container to start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"postgres", "oidc"})
@Testcontainers(disabledWithoutDocker = true)
class MembersIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hivekeeper")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-app-role.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
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

    @MockitoBean
    KeycloakAdminClient keycloak;   // no real Keycloak in CI; the live Admin API shape is proven by curl probes
    @Autowired
    private SetupService setup;
    @Autowired
    private MemberService members;
    @Autowired
    private AccessService access;

    @Test
    void addsListsReRolesAndRemovesAMemberUnderRealRls() {
        // bootstrap the first org + owner (setup uses the 4-arg createUser with a permanent password)
        when(keycloak.createUser(eq("owner"), any(), eq("pw"), any())).thenReturn("kc-owner");
        String tenant = setup.setup(setup.setupToken(), "Acme Corp", "owner", "pw", "o@x", "Olivia Owner")
                .tenantId();
        assertEquals("acme-corp", tenant);

        // add a teammate as a viewer (the add path uses the 5-arg createUser with temporary = true)
        when(keycloak.createUser(eq("bob"), any(), any(), any(), eq(true))).thenReturn("kc-bob");
        String bob = members.add(tenant, "bob", "b@x", "tmp-pw", "Bob Builder", Role.VIEWER);

        // both people show up, and bob resolves as a viewer (not an admin) under real RLS + grant resolution
        List<MemberService.Member> roster = members.list(tenant);
        assertEquals(2, roster.size());
        assertTrue(access.allows(tenant, bob, Role.VIEWER, ResourceScope.org()));
        assertFalse(access.allows(tenant, bob, Role.ADMIN, ResourceScope.org()));

        // promote bob -> admin updates the SAME org grant (no stacking) and he is now an admin
        assertTrue(members.setRole(tenant, bob, Role.ADMIN));
        assertTrue(access.allows(tenant, bob, Role.ADMIN, ResourceScope.org()));
        assertEquals(1, members.ownerCount(tenant));   // the bootstrap owner is still the only owner

        // remove bob -> his membership (and the cascaded grant) are gone; only the owner remains
        assertTrue(members.remove(tenant, bob));
        assertEquals(1, members.list(tenant).size());
        assertFalse(access.allows(tenant, bob, Role.VIEWER, ResourceScope.org()));
    }
}
