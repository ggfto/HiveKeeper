package io.hivekeeper.gateway.setup;

import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end first-run setup against a real Postgres (the RESTRICTED app role + RLS, which unit tests can't
 * exercise): no 'demo' profile, so the gateway boots uninitialized. Keycloak is mocked (the live Admin API
 * shape is proven separately), but everything else is real — the unauthenticated /api/setup route, the INSERT
 * grant on tenant, the RLS tenant-context for the membership/role_grant writes, the just-in-time app_user, and
 * the self-locking behaviour. Self-skips without a container engine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"postgres", "oidc"})
@Testcontainers(disabledWithoutDocker = true)
class SetupIT {

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
    private AccessService access;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("rawtypes")
    void bootstrapsTheFirstOrgAndAdminThenLocks() {
        assertEquals(Boolean.FALSE, status().get("initialized"), "starts uninitialized");

        when(keycloak.createUser(eq("admin"), any(), eq("pw"), any())).thenReturn("kc-sub-1");

        Map<String, Object> body = Map.of(
                "setupToken", setup.setupToken(), "orgName", "Acme Corp",
                "username", "admin", "password", "pw", "email", "a@x", "name", "Olivia Owner");

        ResponseEntity<Map> created = rest.postForEntity("/api/setup", body, Map.class);
        assertEquals(200, created.getStatusCode().value());
        assertEquals("acme-corp", created.getBody().get("tenantId"));   // org name -> tenant id

        assertEquals(Boolean.TRUE, status().get("initialized"), "now initialized");

        // the org and the admin's just-in-time app_user (keyed by the Keycloak subject) exist...
        assertEquals(1, count("select count(*) from tenant where tenant_id = 'acme-corp'"));
        String userId = jdbc.queryForObject(
                "select user_id from app_user where oidc_subject = 'kc-sub-1'", String.class);
        // ...and resolving the grant under real RLS shows the admin is OWNER of the new org
        assertTrue(access.allows("acme-corp", userId, Role.OWNER, ResourceScope.org()));

        // locked: a second setup is refused
        assertEquals(409, rest.postForEntity("/api/setup", body, Map.class).getStatusCode().value());
    }

    @SuppressWarnings("rawtypes")
    private Map status() {
        return rest.getForObject("/api/setup/status", Map.class);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
