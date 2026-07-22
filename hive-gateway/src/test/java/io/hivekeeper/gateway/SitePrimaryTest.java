package io.hivekeeper.gateway;

import io.hivekeeper.gateway.fleet.FleetService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The serving-agent election: among a device's reachable agents that are connected, exactly one — the first
 * by id — serves it, and it moves automatically as agents come and go. There is no single-pin fallback: a
 * device with no connected reachable agent has no serving agent, and the caller skips it.
 */
class SitePrimaryTest {

    private final AgentRegistry registry = mock(AgentRegistry.class);
    private final FleetService fleet = mock(FleetService.class);
    private final SitePrimary sitePrimary = new SitePrimary(registry, fleet);

    private void deviceReachableBy(String... agentIds) {
        when(fleet.agentIdsForDevice("acme", "dev-1")).thenReturn(List.of(agentIds));
    }

    private void connected(String... agentIds) {
        when(registry.agentIds("acme")).thenReturn(Set.of(agentIds));
    }

    @Test
    void picksTheFirstConnectedAgentAsServing() {
        deviceReachableBy("site-a-01", "site-a-02");
        connected("site-a-01", "site-a-02");

        assertEquals("site-a-01", sitePrimary.primaryForDevice("acme", "dev-1").orElseThrow());
    }

    @Test
    void failsOverToTheStandbyWhenThePrimaryIsGone() {
        deviceReachableBy("site-a-01", "site-a-02");
        connected("site-a-02");   // the primary dropped

        assertEquals("site-a-02", sitePrimary.primaryForDevice("acme", "dev-1").orElseThrow());
    }

    @Test
    void failsBackWhenThePrimaryReturns() {
        deviceReachableBy("site-a-01", "site-a-02");
        connected("site-a-01", "site-a-02");   // it is back

        assertEquals("site-a-01", sitePrimary.primaryForDevice("acme", "dev-1").orElseThrow(),
                "the deterministic order resumes the primary automatically");
    }

    @Test
    void hasNoServingAgentWhenNoReachableAgentIsConnected() {
        deviceReachableBy("site-a-01", "site-a-02");
        connected("some-other-agent");

        assertTrue(sitePrimary.primaryForDevice("acme", "dev-1").isEmpty());
    }

    @Test
    void servingAgentIsTheFirstConnectedReachableAgent() {
        deviceReachableBy("site-a-01", "site-a-02");
        connected("site-a-02");   // only the standby is up — the work goes to it

        assertEquals("site-a-02", sitePrimary.servingAgentForDevice("acme", "dev-1").orElseThrow());
    }

    @Test
    void servingAgentIsEmptyWhenTheReachableSetIsEmpty() {
        // A device with no reachability rows (unmanaged) resolves to no serving agent — no pin to fall back to.
        when(fleet.agentIdsForDevice("acme", "dev-1")).thenReturn(List.of());
        connected("some-agent");

        assertTrue(sitePrimary.servingAgentForDevice("acme", "dev-1").isEmpty());
    }

    @Test
    void aDeviceWithNoIdNeverResolvesAServingAgent() {
        assertTrue(sitePrimary.primaryForDevice("acme", null).isEmpty());
        assertTrue(sitePrimary.servingAgentForDevice("acme", null).isEmpty());
    }
}
