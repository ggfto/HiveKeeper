package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
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
        final Map<String, String> siteNames = new LinkedHashMap<>();        // siteId -> name
        final Map<String, Group> groups = new LinkedHashMap<>();            // groupId -> Group
        final Map<String, DeviceRow> devices = new LinkedHashMap<>();       // deviceId -> row
        final Map<String, Set<String>> deviceGroups = new LinkedHashMap<>(); // deviceId -> {groupId}
    }

    /** A mutable device row; the public {@link Device} (with its live group list) is built on read. */
    private static final class DeviceRow {
        String deviceId;
        String siteId;
        String agentId;
        String serial;
        String model;
        String label;
        String mgmtIp;
        String credRef;
    }

    private final Map<String, Org> orgs = new HashMap<>();

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

    // -- agent enrollments ------------------------------------------------------

    @Override
    public String createEnrollment(String tenantId, String agentId, String siteId) {
        throw new UnsupportedOperationException(
                "adding agents requires the 'postgres' profile; the demo stack already ships the lab-agent");
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
        if (serial != null && org.devices.values().stream().anyMatch(d -> serial.equals(d.serial))) {
            throw new DuplicateKeyException("a device with serial '" + serial + "' is already registered");
        }
        DeviceRow row = new DeviceRow();
        row.deviceId = "dev-" + UUID.randomUUID();
        row.serial = serial;
        row.model = model;
        row.label = label;
        row.mgmtIp = mgmtIp;
        row.siteId = siteId;
        row.agentId = agentId;
        row.credRef = credRef;
        org.devices.put(row.deviceId, row);
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
        return new Device(d.deviceId, d.siteId, d.agentId, d.serial, d.model, d.label, d.mgmtIp, d.credRef, groups);
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
