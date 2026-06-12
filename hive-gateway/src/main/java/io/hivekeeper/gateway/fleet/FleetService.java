package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for the organization's fleet — sites, groups, devices, and the device↔group tagging. Every
 * access sets the transaction-local tenant context that the RLS policies key off, so the database enforces
 * that one organization can never read or write another's fleet. Only present under the {@code postgres}
 * profile. Authorization (which user may call these) is layered on top in the controller via
 * {@link io.hivekeeper.gateway.access.AccessService}; this layer only enforces tenant isolation.
 */
@Service
@Profile("postgres")
public class FleetService {

    public record Site(String siteId, String name) {
    }

    public record Group(String groupId, String siteId, String name) {
    }

    public record Device(String deviceId, String siteId, String agentId, String serial, String model,
                         String label, String mgmtIp, List<String> groups) {
    }

    private static final RowMapper<Site> SITE = (rs, n) -> new Site(rs.getString("site_id"), rs.getString("name"));
    private static final RowMapper<Group> GROUP =
            (rs, n) -> new Group(rs.getString("group_id"), rs.getString("site_id"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public FleetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- sites ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Site> listSites(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select site_id, name from site order by name", SITE);
    }

    @Transactional
    public String createSite(String tenantId, String name) {
        setTenant(tenantId);
        String id = "site-" + UUID.randomUUID();
        jdbc.update("insert into site (site_id, tenant_id, name) values (?, ?, ?)", id, tenantId, name);
        return id;
    }

    // -- groups -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Group> listGroups(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select group_id, site_id, name from fleet_group order by name", GROUP);
    }

    @Transactional
    public String createGroup(String tenantId, String name, String siteId) {
        setTenant(tenantId);
        String id = "grp-" + UUID.randomUUID();
        jdbc.update("insert into fleet_group (group_id, tenant_id, site_id, name) values (?, ?, ?, ?)",
                id, tenantId, siteId, name);
        return id;
    }

    // -- devices ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Device> listDevices(String tenantId) {
        setTenant(tenantId);
        Map<String, List<String>> groupsByDevice = groupsByDevice(tenantId);
        return jdbc.query("select device_id, site_id, agent_id, serial, model, label, mgmt_ip from device "
                + "order by coalesce(label, serial, device_id)", (rs, n) -> new Device(
                rs.getString("device_id"), rs.getString("site_id"), rs.getString("agent_id"),
                rs.getString("serial"), rs.getString("model"), rs.getString("label"), rs.getString("mgmt_ip"),
                groupsByDevice.getOrDefault(rs.getString("device_id"), List.of())));
    }

    @Transactional
    public String registerDevice(String tenantId, String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId) {
        setTenant(tenantId);
        String id = "dev-" + UUID.randomUUID();
        jdbc.update("insert into device (device_id, tenant_id, site_id, agent_id, serial, model, label, mgmt_ip) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, siteId, agentId, serial, model, label, mgmtIp);
        return id;
    }

    /** Tag a device into a group (idempotent — re-tagging is a no-op). */
    @Transactional
    public void tagDevice(String tenantId, String deviceId, String groupId) {
        setTenant(tenantId);
        jdbc.update("insert into device_group (tenant_id, device_id, group_id) values (?, ?, ?) "
                + "on conflict do nothing", tenantId, deviceId, groupId);
    }

    /** The lineage of a group — its site (null for a cross-site tag) — so a SITE grant correctly covers a
     *  site-pinned group. Empty if the group does not exist. Derived from the DB, not from the caller. */
    @Transactional(readOnly = true)
    public Optional<ResourceScope> groupScope(String tenantId, String groupId) {
        setTenant(tenantId);
        List<String> sites = jdbc.queryForList(
                "select site_id from fleet_group where group_id = ?", String.class, groupId);
        if (sites.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ResourceScope.group(sites.get(0), groupId));
    }

    /** The lineage (site + groups) used to authorize actions on a device, or empty if it does not exist. */
    @Transactional(readOnly = true)
    public Optional<ResourceScope> deviceScope(String tenantId, String deviceId) {
        setTenant(tenantId);
        List<String> sites = jdbc.queryForList(
                "select site_id from device where device_id = ?", String.class, deviceId);
        if (sites.isEmpty()) {
            return Optional.empty();
        }
        Set<String> groups = Set.copyOf(jdbc.queryForList(
                "select group_id from device_group where device_id = ?", String.class, deviceId));
        return Optional.of(ResourceScope.device(sites.get(0), groups));
    }

    private Map<String, List<String>> groupsByDevice(String tenantId) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        jdbc.query("select device_id, group_id from device_group", rs -> {
            map.computeIfAbsent(rs.getString("device_id"), k -> new ArrayList<>()).add(rs.getString("group_id"));
        });
        return map;
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }
}
