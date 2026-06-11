package io.hivekeeper.gateway.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTenantStoreTest {

    private final TenantStore store = new InMemoryTenantStore();

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
