package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The dev/demo fleet store: a process-local, tenant-scoped map store that lets the {@code !postgres} stack run
 * the whole fleet UI (sites, groups, devices, tagging) without a database. It mirrors {@link
 * PostgresFleetService}'s behaviour — per-tenant isolation, a unique site name and device serial (surfaced as a
 * {@link DuplicateKeyException}, mapped to 409 the same way the Postgres path is) — but holds nothing across a
 * restart. Adding new agents is the one gap: enrollment lives in the agent-handshake auth store, so it requires
 * the {@code postgres} profile; the demo stack ships the {@code lab-agent} already. Every method is synchronized
 * because it does compound read-modify-write across the shared maps.
 */
@Service
@Profile("!postgres")
public class InMemoryFleetService implements FleetService {

    /** One organization's fleet. Only ever touched under the service monitor, so plain maps are safe. */
    private static final class Org {
        final Map<String, String> siteNames = new LinkedHashMap<>();          // siteId -> name
        final Map<String, Group> groups = new LinkedHashMap<>();              // groupId -> Group
        final Map<String, DeviceRow> devices = new LinkedHashMap<>();         // deviceId -> row
        final Map<String, Set<String>> deviceGroups = new LinkedHashMap<>();  // deviceId -> {groupId}
        final Map<String, Set<String>> deviceAgents = new LinkedHashMap<>();  // deviceId -> {agentId} (reach)
        final Map<String, AgentSummary> agents = new LinkedHashMap<>();       // agentId -> identity
    }

    /** A mutable device row; the public {@link Device} (with its live group + reachable-agent lists) is built
     *  on read. Reachability lives in {@link Org#deviceAgents}, not here, mirroring the many-to-many tags. */
    private static final class DeviceRow {
        String deviceId;
        String siteId;
        String serial;
        String model;
        String label;
        String mgmtIp;
        String credRef;
    }

    private final Map<String, Org> orgs = new HashMap<>();

    /**
     * Seeds the demo/solo agent identities so the console's agent list has something to show without a
     * database (reachability itself is populated at runtime by {@code registerDevice}). Mirrors {@link
     * io.hivekeeper.gateway.tenant.InMemoryTenantStore}'s seed: the same {@code lab-agent} / {@code
     * local-agent} it enrolls. Sites are left null — the in-memory demo does not seed the site tree.
     */
    public InMemoryFleetService(@Value("${hivekeeper.demo-seed:false}") boolean demoSeed,
                                @Value("${hivekeeper.solo:false}") boolean solo) {
        if (demoSeed) {
            org("acme").agents.put("lab-agent", new AgentSummary("lab-agent", "lab-agent", null, null));
        }
        if (solo) {
            org("local").agents.put("local-agent", new AgentSummary("local-agent", "local-agent", null, null));
        }
    }

    private Org org(String tenantId) {
        return orgs.computeIfAbsent(tenantId, k -> new Org());
    }

    // -- sites ------------------------------------------------------------------

    @Override
    public synchronized List<Site> listSites(String tenantId) {
        return org(tenantId).siteNames.entrySet().stream()
                .map(e -> new Site(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Site::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public synchronized boolean siteExists(String tenantId, String siteId) {
        return org(tenantId).siteNames.containsKey(siteId);
    }

    @Override
    public synchronized String createSite(String tenantId, String name) {
        Org org = org(tenantId);
        if (org.siteNames.containsValue(name)) {
            throw new DuplicateKeyException("a site named '" + name + "' already exists");
        }
        String id = "site-" + UUID.randomUUID();
        org.siteNames.put(id, name);
        return id;
    }

    @Override
    public synchronized void renameSite(String tenantId, String siteId, String name) {
        Org org = org(tenantId);
        for (Map.Entry<String, String> e : org.siteNames.entrySet()) {
            if (!e.getKey().equals(siteId) && e.getValue().equals(name)) {
                throw new DuplicateKeyException("a site named '" + name + "' already exists");
            }
        }
        if (org.siteNames.containsKey(siteId)) {
            org.siteNames.put(siteId, name);
        }
    }

    @Override
    public synchronized int siteDependents(String tenantId, String siteId) {
        Org org = org(tenantId);
        long devices = org.devices.values().stream().filter(d -> siteId.equals(d.siteId)).count();
        long groups = org.groups.values().stream().filter(g -> siteId.equals(g.siteId())).count();
        return (int) (devices + groups);
    }

    @Override
    public synchronized void deleteSite(String tenantId, String siteId) {
        org(tenantId).siteNames.remove(siteId);
    }

    // -- groups -----------------------------------------------------------------

    @Override
    public synchronized List<Group> listGroups(String tenantId) {
        return org(tenantId).groups.values().stream()
                .sorted(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public synchronized String createGroup(String tenantId, String name, String siteId) {
        String id = "grp-" + UUID.randomUUID();
        org(tenantId).groups.put(id, new Group(id, siteId, name));
        return id;
    }

    @Override
    public synchronized void updateGroup(String tenantId, String groupId, String name, String siteId) {
        Org org = org(tenantId);
        if (org.groups.containsKey(groupId)) {
            org.groups.put(groupId, new Group(groupId, siteId, name));
        }
    }

    @Override
    public synchronized void deleteGroup(String tenantId, String groupId) {
        Org org = org(tenantId);
        org.groups.remove(groupId);
        org.deviceGroups.values().forEach(set -> set.remove(groupId));   // device tags cascade away
    }

    // -- agents + enrollments ---------------------------------------------------

    @Override
    public String createEnrollment(String tenantId, String agentId, String siteId) {
        throw new UnsupportedOperationException(
                "adding agents requires the 'postgres' profile; the demo stack already ships the lab-agent");
    }

    @Override
    public synchronized List<AgentSummary> listAgents(String tenantId) {
        return org(tenantId).agents.values().stream()
                .sorted(Comparator.comparing(AgentSummary::agentId)).toList();
    }

    @Override
    public synchronized List<String> agentIdsForDevice(String tenantId, String deviceId) {
        // Sorted for a deterministic primary. The in-memory store tracks no revocation (that lives in the
        // tenant store), so — as with the single-pin default it replaces — it does not filter revoked agents.
        return org(tenantId).deviceAgents.getOrDefault(deviceId, Set.of()).stream().sorted().toList();
    }

    @Override
    public synchronized List<String> reachablePeers(String tenantId, String agentId) {
        Set<String> peers = new TreeSet<>();
        for (Set<String> reach : org(tenantId).deviceAgents.values()) {
            if (reach.contains(agentId)) {
                peers.addAll(reach);   // every agent that co-reaches a device with this one
            }
        }
        peers.remove(agentId);
        return List.copyOf(peers);
    }

    @Override
    public synchronized List<String> reachableAgents(String tenantId, String deviceId) {
        return agentIdsForDevice(tenantId, deviceId);
    }

    @Override
    public synchronized void addDeviceAgent(String tenantId, String deviceId, String agentId) {
        org(tenantId).deviceAgents.computeIfAbsent(deviceId, k -> new LinkedHashSet<>()).add(agentId);
    }

    @Override
    public synchronized void removeDeviceAgent(String tenantId, String deviceId, String agentId) {
        Set<String> set = org(tenantId).deviceAgents.get(deviceId);
        if (set != null) {
            set.remove(agentId);
        }
    }

    // -- devices ----------------------------------------------------------------

    @Override
    public synchronized List<Device> listDevices(String tenantId) {
        Org org = org(tenantId);
        return org.devices.values().stream()
                .map(d -> toDevice(org, d))
                .sorted(Comparator.comparing((Device d) -> firstNonNull(d.label(), d.serial(), d.deviceId()),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public synchronized String registerDevice(String tenantId, String serial, String model, String label,
                                              String mgmtIp, String siteId, String agentId, String credRef) {
        Org org = org(tenantId);
        // Idempotent on serial, mirroring the Postgres upsert: re-adopting the same AP (e.g. from a backup
        // agent) updates the existing row instead of creating a duplicate. The existing credential is kept
        // when the re-adopt supplies none.
        DeviceRow row = serial == null ? null
                : org.devices.values().stream().filter(d -> serial.equals(d.serial)).findFirst().orElse(null);
        boolean isNew = row == null;
        if (isNew) {
            row = new DeviceRow();
            row.deviceId = "dev-" + UUID.randomUUID();
            row.serial = serial;
            row.siteId = siteId;                 // logical site is set on first registration only
            org.devices.put(row.deviceId, row);
        } else if (credRef == null) {
            credRef = row.credRef;   // keep the existing credential on a plain re-adopt
        }
        row.model = model;
        row.label = label;
        row.mgmtIp = mgmtIp;
        // A backup agent re-adopting must not relocate the device, so only fill the site when it was unset.
        if (row.siteId == null) {
            row.siteId = siteId;
        }
        row.credRef = credRef;
        // Adopting through an agent adds it to the reachability set (both agents can drive the AP); it never
        // displaces a previously-adopting agent.
        if (agentId != null) {
            org.deviceAgents.computeIfAbsent(row.deviceId, k -> new LinkedHashSet<>()).add(agentId);
        }
        return row.deviceId;
    }

    @Override
    public synchronized List<Device> devicesFor(String tenantId, String siteId, String groupId) {
        Org org = org(tenantId);
        return org.devices.values().stream()
                .filter(d -> {
                    if (groupId != null && !groupId.isBlank()) {
                        return org.deviceGroups.getOrDefault(d.deviceId, Set.of()).contains(groupId);
                    }
                    if (siteId != null && !siteId.isBlank()) {
                        return siteId.equals(d.siteId);
                    }
                    return true;
                })
                .map(d -> toDevice(org, d))
                .toList();
    }

    @Override
    public synchronized Optional<String> credRefForHost(String tenantId, String host) {
        return org(tenantId).devices.values().stream()
                .filter(d -> host != null && host.equals(d.mgmtIp) && d.credRef != null)
                .map(d -> d.credRef)
                .findFirst();
    }

    @Override
    public synchronized void tagDevice(String tenantId, String deviceId, String groupId) {
        org(tenantId).deviceGroups.computeIfAbsent(deviceId, k -> new LinkedHashSet<>()).add(groupId);
    }

    @Override
    public synchronized void untagDevice(String tenantId, String deviceId, String groupId) {
        Set<String> set = org(tenantId).deviceGroups.get(deviceId);
        if (set != null) {
            set.remove(groupId);
        }
    }

    @Override
    public synchronized void updateDevice(String tenantId, String deviceId, String label, String siteId) {
        DeviceRow d = org(tenantId).devices.get(deviceId);
        if (d != null) {
            if (label != null) {
                d.label = label;
            }
            if (siteId != null) {
                d.siteId = siteId;
            }
        }
    }

    @Override
    public synchronized void setCredRef(String tenantId, String deviceId, String credRef) {
        DeviceRow d = org(tenantId).devices.get(deviceId);
        if (d != null) {
            d.credRef = credRef;
        }
    }

    @Override
    public synchronized Optional<ResourceScope> groupScope(String tenantId, String groupId) {
        Group g = org(tenantId).groups.get(groupId);
        return g == null ? Optional.empty() : Optional.of(ResourceScope.group(g.siteId(), groupId));
    }

    @Override
    public synchronized Optional<ResourceScope> deviceScope(String tenantId, String deviceId) {
        Org org = org(tenantId);
        DeviceRow d = org.devices.get(deviceId);
        if (d == null) {
            return Optional.empty();
        }
        Set<String> groups = Set.copyOf(org.deviceGroups.getOrDefault(deviceId, Set.of()));
        return Optional.of(ResourceScope.device(d.siteId, groups));
    }

    private static Device toDevice(Org org, DeviceRow d) {
        List<String> groups = List.copyOf(org.deviceGroups.getOrDefault(d.deviceId, Set.of()));
        List<String> agents = org.deviceAgents.getOrDefault(d.deviceId, Set.of()).stream().sorted().toList();
        return new Device(d.deviceId, d.siteId, d.serial, d.model, d.label, d.mgmtIp, d.credRef, groups, agents);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return "";
    }
}
