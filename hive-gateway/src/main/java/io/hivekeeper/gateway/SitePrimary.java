package io.hivekeeper.gateway;

import io.hivekeeper.gateway.fleet.FleetService;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the one agent that should serve a DEVICE right now, when more than one can reach it.
 *
 * <p>Two or more agents that reach the same access point are an active/standby (or load-shared) set — all can
 * drive it, but only one should do a given piece of unattended work, or a backup capture runs twice and a
 * config applies twice. The gateway is the natural place to decide that: it terminates every agent connection,
 * so it already knows which agents are live, and it is the single authority, so there is no split-brain to
 * negotiate. The agents never talk to each other and never learn a peer exists.
 *
 * <p>The choice is deterministic: among the device's reachable agents that are currently connected, the one
 * whose id sorts first. Deterministic is what makes failover and failback free — when the serving agent drops,
 * the next connected reachable agent serves on the next dispatch; when it returns, it resumes. Since the
 * operator chooses each agent's id ({@code HIVEKEEPER_AGENT_ID}), they choose the primary by naming it to sort
 * first (e.g. {@code site-a-01} ahead of {@code site-a-02}).
 *
 * <p>This is only consulted for UNATTENDED work — the background poller and scope-targeted bulk operations.
 * A single-device console op still goes to the agent the operator picked in the request; the human chose,
 * and this does not second-guess them. Empty means no reachable agent is connected — the device is currently
 * unreachable, and the caller skips it rather than guessing.
 */
@Component
public class SitePrimary {

    private final AgentRegistry registry;
    private final FleetService fleet;

    public SitePrimary(AgentRegistry registry, FleetService fleet) {
        this.registry = registry;
        this.fleet = fleet;
    }

    /**
     * The agent that should serve a device now, or empty when none of its reachable agents is connected. When
     * the device has exactly one reachable agent this is just that agent (when connected) — the common case.
     */
    public Optional<String> primaryForDevice(String tenantId, String deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        Set<String> connected = registry.agentIds(tenantId);
        return fleet.agentIdsForDevice(tenantId, deviceId).stream()
                .filter(connected::contains)
                .findFirst();   // agentIdsForDevice is ordered, so first-connected is the serving agent
    }

    /**
     * The agent to actually run a device's task on: the first connected agent in the device's reachable set,
     * or empty when none is connected (there is no single-pin fallback — reachability is the whole truth now).
     */
    public Optional<String> servingAgentForDevice(String tenantId, String deviceId) {
        return primaryForDevice(tenantId, deviceId);
    }
}
