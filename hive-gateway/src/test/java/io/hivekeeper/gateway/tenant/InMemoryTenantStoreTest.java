package io.hivekeeper.gateway.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTenantStoreTest {

    private final TenantStore store = new InMemoryTenantStore(true, false);   // demo-seeded

    @Test
    void isEmptyByDefault() {
        // The default (neither demo-seed nor solo) must ship NO known-public credentials.
        TenantStore unseeded = new InMemoryTenantStore(false, false);
        assertTrue(unseeded.tenantByApiKey("acme-key").isEmpty());
        assertTrue(unseeded.enrollmentByToken("enroll-lab-agent").isEmpty());
    }

    @Test
    void soloSeedsASingleLocalTenantAndAgent() {
        TenantStore solo = new InMemoryTenantStore(false, true);
        assertEquals("local", solo.tenant("local").orElseThrow().tenantId());
        assertTrue(solo.tenantByApiKey("acme-key").isEmpty());   // no demo tenants when only solo is on
        AgentEnrollment enrollment = solo.enrollmentByToken("enroll-local").orElseThrow();
        assertEquals("local-agent", enrollment.agentId());
        assertEquals("local", enrollment.tenantId());
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
