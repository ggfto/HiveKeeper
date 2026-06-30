package io.hivekeeper.gateway.tenant;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revocation + re-enrollment on the in-memory store (the seed/demo + solo stack). The demo seed enrolls
 * {@code lab-agent} in tenant {@code acme}.
 */
class InMemoryTenantStoreRevocationTest {

    private final InMemoryTenantStore store = new InMemoryTenantStore(true, false);

    @Test
    void revokeMarksTheAgentAndIsScopedToItsTenant() {
        assertFalse(store.isAgentRevoked("lab-agent"));

        // A foreign tenant cannot revoke another tenant's agent.
        assertFalse(store.revokeAgent("globex", "lab-agent", null));
        assertFalse(store.isAgentRevoked("lab-agent"));

        assertTrue(store.revokeAgent("acme", "lab-agent", "compromised"));
        assertTrue(store.isAgentRevoked("lab-agent"));
        // Idempotent.
        assertTrue(store.revokeAgent("acme", "lab-agent", "again"));
        assertTrue(store.isAgentRevoked("lab-agent"));
    }

    @Test
    void revokeOfAnUnknownAgentReturnsFalse() {
        assertFalse(store.revokeAgent("acme", "ghost", null));
    }

    @Test
    void reEnrollIssuesAFreshTokenAndClearsRevocationAndConsumption() {
        // Consume + revoke the original enrollment.
        assertTrue(store.markEnrollmentConsumed("enroll-lab-agent"));
        assertTrue(store.revokeAgent("acme", "lab-agent", "rotate"));

        Optional<String> newToken = store.reEnrollAgent("acme", "lab-agent");
        assertTrue(newToken.isPresent());
        assertNotEquals("enroll-lab-agent", newToken.get());

        // No longer revoked; the OLD token is gone, the NEW token resolves to the same agent and is unconsumed.
        assertFalse(store.isAgentRevoked("lab-agent"));
        assertTrue(store.enrollmentByToken("enroll-lab-agent").isEmpty());
        assertTrue(store.enrollmentByToken(newToken.get())
                .filter(e -> e.agentId().equals("lab-agent")).isPresent());
        assertTrue(store.markEnrollmentConsumed(newToken.get()), "the fresh token is unconsumed");
    }

    @Test
    void reEnrollOfAForeignOrUnknownAgentIsEmpty() {
        assertTrue(store.reEnrollAgent("globex", "lab-agent").isEmpty());
        assertTrue(store.reEnrollAgent("acme", "ghost").isEmpty());
    }
}
