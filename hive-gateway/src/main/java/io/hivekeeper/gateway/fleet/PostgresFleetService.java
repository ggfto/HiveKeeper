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
 * The real fleet store: a shared-schema Postgres with row-level security. Every access sets the
 * transaction-local tenant context that the RLS policies key off, so the database enforces that one
 * organization can never read or write another's fleet. Only present under the {@code postgres} profile;
 * the {@code !postgres} dev/demo stack uses {@link InMemoryFleetService} instead.
 */
@Service
@Profile("postgres")
public class PostgresFleetService implements FleetService {

    private static final RowMapper<Site> SITE = (rs, n) -> new Site(rs.getString("site_id"), rs.getString("name"));
    private static final RowMapper<Group> GROUP =
            (rs, n) -> new Group(rs.getString("group_id"), rs.getString("site_id"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public PostgresFleetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- sites ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Site> listSites(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select site_id, name from site order by name", SITE);
    }

    @Override
    @Transactional
    public String createSite(String tenantId, String name) {
        setTenant(tenantId);
        String id = "site-" + UUID.randomUUID();
        jdbc.update("insert into site (site_id, tenant_id, name) values (?, ?, ?)", id, tenantId, name);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean siteExists(String tenantId, String siteId) {
        setTenant(tenantId);
        Integer n = jdbc.queryForObject("select count(*) from site where site_id = ?", Integer.class, siteId);
        return n != null && n > 0;
    }

    @Override
    @Transactional
    public void renameSite(String tenantId, String siteId, String name) {
        setTenant(tenantId);
        jdbc.update("update site set name = ? where site_id = ?", name, siteId);
    }

    @Override
    @Transactional(readOnly = true)
    public int siteDependents(String tenantId, String siteId) {
        setTenant(tenantId);
        Integer devices = jdbc.queryForObject("select count(*) from device where site_id = ?", Integer.class, siteId);
        Integer groups = jdbc.queryForObject(
                "select count(*) from fleet_group where site_id = ?", Integer.class, siteId);
        return (devices == null ? 0 : devices) + (groups == null ? 0 : groups);
    }

    @Override
    @Transactional
    public void deleteSite(String tenantId, String siteId) {
        setTenant(tenantId);
        jdbc.update("delete from site where site_id = ?", siteId);
    }

    // -- groups -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Group> listGroups(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select group_id, site_id, name from fleet_group order by name", GROUP);
    }

    @Override
    @Transactional
    public String createGroup(String tenantId, String name, String siteId) {
        setTenant(tenantId);
        String id = "grp-" + UUID.randomUUID();
        jdbc.update("insert into fleet_group (group_id, tenant_id, site_id, name) values (?, ?, ?, ?)",
                id, tenantId, siteId, name);
        return id;
    }

    @Override
    @Transactional
    public void updateGroup(String tenantId, String groupId, String name, String siteId) {
        setTenant(tenantId);
        // Sets both name and site; re-pinning to another tenant's site is rejected by the composite FK, and a
        // null site_id (cross-site tag) simply skips that FK. RLS scopes the update to this tenant's rows.
        jdbc.update("update fleet_group set name = ?, site_id = ? where group_id = ?", name, siteId, groupId);
    }

    @Override
    @Transactional
    public void deleteGroup(String tenantId, String groupId) {
        setTenant(tenantId);
        jdbc.update("delete from fleet_group where group_id = ?", groupId);
    }

    // -- agent enrollments ------------------------------------------------------

    @Override
    @Transactional
    public String createEnrollment(String tenantId, String agentId, String siteId) {
        setTenant(tenantId);
        String token = "enroll-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into agent_enrollment (token, agent_id, tenant_id, site_id) values (?, ?, ?, ?)",
                token, agentId, tenantId, siteId);
        return token;
    }

    // -- devices ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Device> listDevices(String tenantId) {
        setTenant(tenantId);
        Map<String, List<String>> groupsByDevice = groupsByDevice(tenantId);
        return jdbc.query("select device_id, site_id, agent_id, serial, model, label, mgmt_ip, cred_ref from device "
                + "order by coalesce(label, serial, device_id)", (rs, n) -> new Device(
                rs.getString("device_id"), rs.getString("site_id"), rs.getString("agent_id"),
                rs.getString("serial"), rs.getString("model"), rs.getString("label"), rs.getString("mgmt_ip"),
                rs.getString("cred_ref"),
                groupsByDevice.getOrDefault(rs.getString("device_id"), List.of())));
    }

    @Override
    @Transactional
    public String registerDevice(String tenantId, String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId, String credRef) {
        setTenant(tenantId);
        String id = "dev-" + UUID.randomUUID();
        // Idempotent on the AP's serial (unique per org): adopting the same access point again — including
        // from a second, backup agent on the same site — updates the one row rather than failing on the
        // unique constraint or creating a duplicate that would then be dispatched to twice. The existing
        // credential is kept when the re-adopt does not supply one, so a plain re-adopt never wipes it.
        return jdbc.queryForObject(
                "insert into device (device_id, tenant_id, site_id, agent_id, serial, model, label, "
                        + "mgmt_ip, cred_ref) values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (tenant_id, serial) do update set "
                        + "site_id = excluded.site_id, agent_id = excluded.agent_id, model = excluded.model, "
                        + "label = excluded.label, mgmt_ip = excluded.mgmt_ip, "
                        + "cred_ref = coalesce(excluded.cred_ref, device.cred_ref) "
                        + "returning device_id",
                String.class, id, tenantId, siteId, agentId, serial, model, label, mgmtIp, credRef);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Device> devicesFor(String tenantId, String siteId, String groupId) {
        setTenant(tenantId);
        Map<String, List<String>> groupsByDevice = groupsByDevice(tenantId);
        RowMapper<Device> mapper = (rs, n) -> new Device(rs.getString("device_id"), rs.getString("site_id"),
                rs.getString("agent_id"), rs.getString("serial"), rs.getString("model"), rs.getString("label"),
                rs.getString("mgmt_ip"), rs.getString("cred_ref"),
                groupsByDevice.getOrDefault(rs.getString("device_id"), List.of()));
        if (groupId != null && !groupId.isBlank()) {
            return jdbc.query("select d.device_id, d.site_id, d.agent_id, d.serial, d.model, d.label, d.mgmt_ip, "
                            + "d.cred_ref from device d join device_group dg on d.device_id = dg.device_id "
                            + "where dg.group_id = ?", mapper, groupId);
        }
        if (siteId != null && !siteId.isBlank()) {
            return jdbc.query("select device_id, site_id, agent_id, serial, model, label, mgmt_ip, cred_ref "
                    + "from device where site_id = ?", mapper, siteId);
        }
        return jdbc.query("select device_id, site_id, agent_id, serial, model, label, mgmt_ip, cred_ref from device",
                mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> credRefForHost(String tenantId, String host) {
        setTenant(tenantId);
        return jdbc.queryForList("select cred_ref from device where mgmt_ip = ?", String.class, host)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    @Transactional
    public void tagDevice(String tenantId, String deviceId, String groupId) {
        setTenant(tenantId);
        jdbc.update("insert into device_group (tenant_id, device_id, group_id) values (?, ?, ?) "
                + "on conflict do nothing", tenantId, deviceId, groupId);
    }

    @Override
    @Transactional
    public void untagDevice(String tenantId, String deviceId, String groupId) {
        setTenant(tenantId);
        jdbc.update("delete from device_group where device_id = ? and group_id = ?", deviceId, groupId);
    }

    @Override
    @Transactional
    public void updateDevice(String tenantId, String deviceId, String label, String siteId) {
        setTenant(tenantId);
        jdbc.update("update device set label = coalesce(?, label), site_id = coalesce(?, site_id) "
                + "where device_id = ?", label, siteId, deviceId);
    }

    @Override
    @Transactional
    public void setCredRef(String tenantId, String deviceId, String credRef) {
        setTenant(tenantId);
        jdbc.update("update device set cred_ref = ? where device_id = ?", credRef, deviceId);
    }

    @Override
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

    @Override
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
