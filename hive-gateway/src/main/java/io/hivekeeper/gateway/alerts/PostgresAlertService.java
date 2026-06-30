package io.hivekeeper.gateway.alerts;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The real alert store: shared-schema Postgres with row-level security as the hard wall between organizations
 * (every method sets {@code app.current_tenant} first, like {@code PostgresFleetService}).
 */
@Service
@Profile("postgres")
public class PostgresAlertService implements AlertService {

    private static final RowMapper<Channel> CHANNEL = (rs, n) -> new Channel(
            rs.getString("channel_id"), rs.getString("type"), rs.getString("target"),
            rs.getString("min_severity"), rs.getBoolean("enabled"), instant(rs.getTimestamp("created_at")));

    private static final RowMapper<FiringAlert> FIRING = (rs, n) -> new FiringAlert(
            rs.getString("device_id"), rs.getString("agent_id"), rs.getString("alert_id"),
            rs.getString("severity"), rs.getString("message"), instant(rs.getTimestamp("first_seen")),
            instant(rs.getTimestamp("last_seen")));

    private final JdbcTemplate jdbc;

    public PostgresAlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Settings settings(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select max_stations, poll_enabled from alert_settings where tenant_id = ?",
                        (rs, n) -> new Settings(rs.getInt("max_stations"), rs.getBoolean("poll_enabled")), tenantId)
                .stream().findFirst().orElse(Settings.DEFAULT);
    }

    @Override
    @Transactional
    public void saveSettings(String tenantId, int maxStations, boolean pollEnabled) {
        setTenant(tenantId);
        jdbc.update("insert into alert_settings (tenant_id, max_stations, poll_enabled, updated_at) "
                        + "values (?, ?, ?, now()) on conflict (tenant_id) do update set "
                        + "max_stations = excluded.max_stations, poll_enabled = excluded.poll_enabled, updated_at = now()",
                tenantId, Math.max(1, maxStations), pollEnabled);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Channel> channels(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select channel_id, type, target, min_severity, enabled, created_at from alert_channel "
                + "order by created_at", CHANNEL);
    }

    @Override
    @Transactional
    public String addChannel(String tenantId, String type, String target, String minSeverity) {
        setTenant(tenantId);
        String id = "ch-" + UUID.randomUUID();
        jdbc.update("insert into alert_channel (channel_id, tenant_id, type, target, min_severity) "
                + "values (?, ?, ?, ?, ?)", id, tenantId, type, target, minSeverity);
        return id;
    }

    @Override
    @Transactional
    public void setChannelEnabled(String tenantId, String channelId, boolean enabled) {
        setTenant(tenantId);
        jdbc.update("update alert_channel set enabled = ? where channel_id = ?", enabled, channelId);
    }

    @Override
    @Transactional
    public void removeChannel(String tenantId, String channelId) {
        setTenant(tenantId);
        jdbc.update("delete from alert_channel where channel_id = ?", channelId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FiringAlert> firing(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select device_id, agent_id, alert_id, severity, message, first_seen, last_seen "
                + "from fleet_alert", FIRING);
    }

    @Override
    @Transactional
    public void markFiring(String tenantId, FiringAlert a) {
        setTenant(tenantId);
        jdbc.update("insert into fleet_alert (tenant_id, device_id, alert_id, agent_id, severity, message) "
                        + "values (?, ?, ?, ?, ?, ?) on conflict (tenant_id, device_id, alert_id) do update set "
                        + "severity = excluded.severity, message = excluded.message, last_seen = now()",
                tenantId, a.deviceId(), a.alertId(), a.agentId(), a.severity(), a.message());
    }

    @Override
    @Transactional
    public void resolveFiring(String tenantId, String deviceId, String alertId) {
        setTenant(tenantId);
        jdbc.update("delete from fleet_alert where device_id = ? and alert_id = ?", deviceId, alertId);
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
