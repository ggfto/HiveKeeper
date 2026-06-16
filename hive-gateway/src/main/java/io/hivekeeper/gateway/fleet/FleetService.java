package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
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

    record Device(String deviceId, String siteId, String agentId, String serial, String model,
                  String label, String mgmtIp, String credRef, List<String> groups) {
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

    void renameGroup(String tenantId, String groupId, String name);

    void deleteGroup(String tenantId, String groupId);

    // -- agent enrollments ------------------------------------------------------

    String createEnrollment(String tenantId, String agentId, String siteId);

    // -- devices ----------------------------------------------------------------

    List<Device> listDevices(String tenantId);

    String registerDevice(String tenantId, String serial, String model, String label, String mgmtIp,
                          String siteId, String agentId, String credRef);

    List<Device> devicesFor(String tenantId, String siteId, String groupId);

    Optional<String> credRefForHost(String tenantId, String host);

    void tagDevice(String tenantId, String deviceId, String groupId);

    void untagDevice(String tenantId, String deviceId, String groupId);

    void updateDevice(String tenantId, String deviceId, String label, String siteId);

    /** The lineage of a group — its site (null for a cross-site tag) — or empty if the group does not exist. */
    Optional<ResourceScope> groupScope(String tenantId, String groupId);

    /** The lineage (site + groups) of a device, or empty if it does not exist. */
    Optional<ResourceScope> deviceScope(String tenantId, String deviceId);
}
