package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the organization's fleet — sites, groups, devices, and the device&lt;-&gt;group tagging. Two
 * implementations back this contract, selected by profile: {@link PostgresFleetService} (the real one — a
 * shared-schema Postgres with row-level security as the hard wall between organizations) and {@link
 * InMemoryFleetService} (a lightweight, RLS-free map store for the no-database dev/demo stack). Both enforce
 * that one organization can never read or write another's fleet; authorization (which user may call these) is
 * layered on top in {@link FleetController}.
 */
public interface FleetService {

    record Site(String siteId, String name) {
    }

    record Group(String groupId, String siteId, String name) {
    }

    /** A managed device. {@code reachableAgents} is the set of agents that can drive it (its {@code
     *  device_agent} rows), sorted by agent id — the first connected one is the deterministic serving agent
     *  for unattended work. Empty means no agent can reach it (unmanaged / offline-only). */
    record Device(String deviceId, String siteId, String serial, String model,
                  String label, String mgmtIp, String credRef, List<String> groups, List<String> reachableAgents) {
    }

    /** An agent's durable identity (the {@code agent} table), independent of whether it is currently
     *  connected. {@code lastSeen} is null until the agent has dialed in at least once. */
    record AgentSummary(String agentId, String name, String siteId, Instant lastSeen) {
    }

    // -- sites ------------------------------------------------------------------

    List<Site> listSites(String tenantId);

    boolean siteExists(String tenantId, String siteId);

    String createSite(String tenantId, String name);

    void renameSite(String tenantId, String siteId, String name);

    /** How many devices + groups are still pinned to the site (a non-zero count blocks deletion). */
    int siteDependents(String tenantId, String siteId);

    void deleteSite(String tenantId, String siteId);

    // -- groups -----------------------------------------------------------------

    List<Group> listGroups(String tenantId);

    String createGroup(String tenantId, String name, String siteId);

    /** Set a group's name and its pinned site (null = a cross-site tag) — used to rename and/or re-pin it. */
    void updateGroup(String tenantId, String groupId, String name, String siteId);

    void deleteGroup(String tenantId, String groupId);

    // -- agents + enrollments ---------------------------------------------------

    /** Register a new agent: creates its durable {@code agent} identity AND its one-time enrollment token
     *  (returned). The two are written together so the identity always exists before anything references it. */
    String createEnrollment(String tenantId, String agentId, String siteId);

    /** Every agent enrolled in the org, by durable identity (connected or not), ordered by agent id. */
    List<AgentSummary> listAgents(String tenantId);

    /** The agents that can reach a device (its reachability set), ordered by agent id and excluding revoked
     *  ones — so the first that is also connected is the deterministic serving agent. */
    List<String> agentIdsForDevice(String tenantId, String deviceId);

    /** Agents that co-reach at least one device with {@code agentId} (its active/standby peers), ordered by
     *  agent id and excluding revoked ones — used to fail a dropped agent's jobs over to a peer. */
    List<String> reachablePeers(String tenantId, String agentId);

    /** The reachability set of a single device, ordered by agent id (revoked included — the console shows all
     *  links and marks state separately). */
    List<String> reachableAgents(String tenantId, String deviceId);

    /** Add an agent to a device's reachability set (idempotent). */
    void addDeviceAgent(String tenantId, String deviceId, String agentId);

    /** Remove an agent from a device's reachability set. */
    void removeDeviceAgent(String tenantId, String deviceId, String agentId);

    // -- devices ----------------------------------------------------------------

    List<Device> listDevices(String tenantId);

    /** Upsert a device (idempotent on serial) and, when {@code agentId} is non-null, add that agent to its
     *  reachability set — so a second agent adopting the same AP extends the set instead of overwriting a
     *  single pin. The device's logical site is set on first registration and preserved on re-adopt (a backup
     *  agent does not relocate it). Returns the (stable) device id. */
    String registerDevice(String tenantId, String serial, String model, String label, String mgmtIp,
                          String siteId, String agentId, String credRef);

    List<Device> devicesFor(String tenantId, String siteId, String groupId);

    Optional<String> credRefForHost(String tenantId, String host);

    void tagDevice(String tenantId, String deviceId, String groupId);

    void untagDevice(String tenantId, String deviceId, String groupId);

    void updateDevice(String tenantId, String deviceId, String label, String siteId);

    /** Points a device at the credential reference the agent resolves locally — set when HiveKeeper manages
     *  the device's credential, so future ops carry the right {@code credRef}. */
    void setCredRef(String tenantId, String deviceId, String credRef);

    /** The lineage of a group — its site (null for a cross-site tag) — or empty if the group does not exist. */
    Optional<ResourceScope> groupScope(String tenantId, String groupId);

    /** The lineage (site + groups) of a device, or empty if it does not exist. */
    Optional<ResourceScope> deviceScope(String tenantId, String deviceId);
}
