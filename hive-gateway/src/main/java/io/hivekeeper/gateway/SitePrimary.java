package io.hivekeeper.gateway;

import io.hivekeeper.gateway.tenant.TenantStore;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the one agent that should serve a site right now, when more than one is enrolled to it.
 *
 * <p>Two or more agents on the same site's LAN are an active/standby pair — both can reach the same access
 * points, but only one should do the work, or a backup capture runs twice and a config applies twice. The
 * gateway is the natural place to decide that: it terminates every agent connection, so it already knows
 * which agents are live, and it is the single authority, so there is no split-brain to negotiate. The agents
 * never talk to each other and never learn a peer exists.
 *
 * <p>The primary is deterministic: among the site's enrolled agents that are currently connected, the one
 * whose id sorts first. Deterministic is what makes failover and failback free — when the primary drops, the
 * next connected agent becomes primary on the next dispatch; when it returns, it resumes. Since the operator
 * chooses each agent's id ({@code HIVEKEEPER_AGENT_ID}), they choose the primary by naming it to sort first
 * (e.g. {@code site-a-01} ahead of {@code site-a-02}).
 *
 * <p>This is only consulted for UNATTENDED work — the background poller and scope-targeted bulk operations.
 * A single-device console op still goes to the agent the operator picked in the request; the human chose,
 * and this does not second-guess them.
 */
@Component
public class SitePrimary {

    private final AgentRegistry registry;
    private final TenantStore tenants;

    public SitePrimary(AgentRegistry registry, TenantStore tenants) {
        this.registry = registry;
        this.tenants = tenants;
    }

    /**
     * The agent that should serve a site now, or empty when none of its agents is connected. When the site
     * has exactly one enrolled agent this is just that agent (when connected) — the common case pays nothing.
     */
    public Optional<String> primaryForSite(String tenantId, String siteId) {
        if (siteId == null) {
            return Optional.empty();
        }
        Set<String> connected = registry.agentIds(tenantId);
        return tenants.agentIdsForSite(tenantId, siteId).stream()
                .filter(connected::contains)
                .findFirst();   // agentIdsForSite is ordered, so first-connected is the primary
    }

    /**
     * The agent to actually run a device's task on: the site's current primary, falling back to the agent
     * pinned on the device row.
     *
     * <p>The fallback keeps every existing deployment working unchanged — a site with one agent, or the
     * in-memory dev store that does not track site membership, resolves to the pinned agent exactly as
     * before. It is only when a second agent is enrolled to the site that the primary takes over.
     */
    public String servingAgent(String tenantId, String siteId, String pinnedAgentId) {
        return primaryForSite(tenantId, siteId).orElse(pinnedAgentId);
    }
}
