package io.hivekeeper.gateway.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTenantStoreTest {

    private final TenantStore store = new InMemoryTenantStore(true);   // demo-seeded

    @Test
    void isEmptyByDefault() {
        // The default (no demo-seed flag) must ship NO known-public credentials.
        TenantStore unseeded = new InMemoryTenantStore(false);
        assertTrue(unseeded.tenantByApiKey("acme-key").isEmpty());
        assertTrue(unseeded.enrollmentByToken("enroll-lab-agent").isEmpty());
    }

    @Test
    void resolvesAgentEnrollmentToItsTenant() {
        AgentEnrollment enrollment = store.enrollmentByToken("enroll-lab-agent").orElseThrow();
        assertEquals("lab-agent", enrollment.agentId());
        assertEquals("acme", enrollment.tenantId());
    }

    @Test
    void rejectsUnknownEnrollmentToken() {
        assertTrue(store.enrollmentByToken("bogus").isEmpty());
        assertTrue(store.enrollmentByToken(null).isEmpty());
    }

    @Test
    void resolvesOperatorByApiKey() {
        assertEquals("acme", store.tenantByApiKey("acme-key").orElseThrow().tenantId());
        assertEquals("globex", store.tenantByApiKey("globex-key").orElseThrow().tenantId());
    }

    @Test
    void rejectsUnknownApiKey() {
        assertTrue(store.tenantByApiKey("nope").isEmpty());
        assertTrue(store.tenantByApiKey(null).isEmpty());
    }
}
