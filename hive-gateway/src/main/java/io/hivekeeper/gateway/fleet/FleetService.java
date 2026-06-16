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
                         String label, String mgmtIp, String credRef, List<String> groups) {
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

    // -- agent enrollments ------------------------------------------------------

    /**
     * Register an agent: mint an enrollment token the operator gives the on-prem agent (it authenticates the
     * WebSocket handshake). agent_enrollment has no RLS (it is an auth-lookup table), but we still set the
     * tenant context for the site FK + consistency. agent_id is globally unique; a clash surfaces as a
     * {@code DataIntegrityViolationException} (the controller maps it to 409).
     */
    @Transactional
    public String createEnrollment(String tenantId, String agentId, String siteId) {
        setTenant(tenantId);
        String token = "enroll-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into agent_enrollment (token, agent_id, tenant_id, site_id) values (?, ?, ?, ?)",
                token, agentId, tenantId, siteId);
        return token;
    }

    // -- devices ----------------------------------------------------------------

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

    @Transactional
    public String registerDevice(String tenantId, String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId, String credRef) {
        setTenant(tenantId);
        String id = "dev-" + UUID.randomUUID();
        jdbc.update("insert into device (device_id, tenant_id, site_id, agent_id, serial, model, label, "
                        + "mgmt_ip, cred_ref) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, siteId, agentId, serial, model, label, mgmtIp, credRef);
        return id;
    }

    /** The registered devices targeted by a bulk op: those in a group, or in a site, or (both null) the whole
     *  org. RLS scopes every query to the tenant. Each device carries its own cred_ref (so bulk uses the row
     *  already in hand instead of re-resolving a credential by management IP) and the groups it is tagged into
     *  (so bulk can re-authorize each device against its OWN lineage — a cross-site group may contain a device
     *  pinned to a different site). */
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

    /** The credential reference for a registered device by its management IP — the opaque pointer the agent
     *  resolves locally to the actual secret. Empty if the host is not a registered device (or has none). */
    @Transactional(readOnly = true)
    public Optional<String> credRefForHost(String tenantId, String host) {
        setTenant(tenantId);
        return jdbc.queryForList("select cred_ref from device where mgmt_ip = ?", String.class, host)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** Tag a device into a group (idempotent — re-tagging is a no-op). */
    @Transactional
    public void tagDevice(String tenantId, String deviceId, String groupId) {
        setTenant(tenantId);
        jdbc.update("insert into device_group (tenant_id, device_id, group_id) values (?, ?, ?) "
                + "on conflict do nothing", tenantId, deviceId, groupId);
    }

    /** Remove a device from a group (idempotent — removing an absent membership is a no-op). */
    @Transactional
    public void untagDevice(String tenantId, String deviceId, String groupId) {
        setTenant(tenantId);
        jdbc.update("delete from device_group where device_id = ? and group_id = ?", deviceId, groupId);
    }

    /** Update a device's HiveKeeper metadata (label and/or its pinned site). A null argument keeps the current
     *  value; the composite {@code (site_id, tenant_id)} FK rejects a site from another tenant, and RLS scopes
     *  the update to this tenant's rows. */
    @Transactional
    public void updateDevice(String tenantId, String deviceId, String label, String siteId) {
        setTenant(tenantId);
        jdbc.update("update device set label = coalesce(?, label), site_id = coalesce(?, site_id) "
                + "where device_id = ?", label, siteId, deviceId);
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
