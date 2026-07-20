package io.hivekeeper.gateway;

import io.hivekeeper.gateway.tenant.TenantStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The active/standby election: among a site's agents that are connected, exactly one — the first by id — is
 * the primary, and it moves automatically as agents come and go.
 */
class SitePrimaryTest {

    private final AgentRegistry registry = mock(AgentRegistry.class);
    private final TenantStore tenants = mock(TenantStore.class);
    private final SitePrimary sitePrimary = new SitePrimary(registry, tenants);

    private void siteHas(String... agentIds) {
        when(tenants.agentIdsForSite("acme", "site-1")).thenReturn(List.of(agentIds));
    }

    private void connected(String... agentIds) {
        when(registry.agentIds("acme")).thenReturn(Set.of(agentIds));
    }

    @Test
    void picksTheFirstConnectedAgentAsPrimary() {
        siteHas("site-a-01", "site-a-02");
        connected("site-a-01", "site-a-02");

        assertEquals("site-a-01", sitePrimary.primaryForSite("acme", "site-1").orElseThrow());
    }

    @Test
    void failsOverToTheStandbyWhenThePrimaryIsGone() {
        siteHas("site-a-01", "site-a-02");
        connected("site-a-02");   // the primary dropped

        assertEquals("site-a-02", sitePrimary.primaryForSite("acme", "site-1").orElseThrow());
    }

    @Test
    void failsBackWhenThePrimaryReturns() {
        siteHas("site-a-01", "site-a-02");
        connected("site-a-01", "site-a-02");   // it is back

        assertEquals("site-a-01", sitePrimary.primaryForSite("acme", "site-1").orElseThrow(),
                "the deterministic order resumes the primary automatically");
    }

    @Test
    void hasNoPrimaryWhenNoAgentOfTheSiteIsConnected() {
        siteHas("site-a-01", "site-a-02");
        connected("some-other-site-agent");

        assertTrue(sitePrimary.primaryForSite("acme", "site-1").isEmpty());
    }

    @Test
    void servingAgentIsThePrimaryWhenTheSiteHasOne() {
        siteHas("site-a-01", "site-a-02");
        connected("site-a-01", "site-a-02");

        // The pinned agent is the standby, but the work must go to the primary.
        assertEquals("site-a-01", sitePrimary.servingAgent("acme", "site-1", "site-a-02"));
    }

    @Test
    void servingAgentFallsBackToThePinnedAgentWhenNoPrimaryIsAvailable() {
        // A single-agent site, or the dev store that does not track site membership: agentIdsForSite is empty,
        // so nothing changes from before — the device's own pinned agent serves it.
        when(tenants.agentIdsForSite("acme", "site-1")).thenReturn(List.of());
        connected("legacy-agent");

        assertEquals("legacy-agent", sitePrimary.servingAgent("acme", "site-1", "legacy-agent"));
    }

    @Test
    void aSiteWithNoIdNeverResolvesAPrimary() {
        assertTrue(sitePrimary.primaryForSite("acme", null).isEmpty());
        assertEquals("pinned", sitePrimary.servingAgent("acme", null, "pinned"));
    }
}
