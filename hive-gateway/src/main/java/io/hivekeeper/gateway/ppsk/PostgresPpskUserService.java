package io.hivekeeper.gateway.ppsk;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The real PPSK-user store: shared-schema Postgres with row-level security as the hard wall between
 * organizations (every method sets {@code app.current_tenant} first, exactly like {@code PostgresFleetService}).
 * Stores metadata + a {@code psk_ref} only — never the usable key, which lives on the on-prem agent.
 */
@Service
@Profile("postgres")
public class PostgresPpskUserService implements PpskUserService {

    private static final RowMapper<PpskUser> USER = (rs, n) -> new PpskUser(
            rs.getString("ppsk_user_id"), rs.getString("agent_id"), rs.getString("security_object"),
            rs.getString("user_group"), rs.getString("username"), rs.getString("psk_ref"),
            (Integer) rs.getObject("user_profile_attr"), (Integer) rs.getObject("vlan_id"),
            rs.getString("schedule_name"), splitMacs(rs.getString("mac_bindings")), rs.getString("status"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("rotated_at")));

    private final JdbcTemplate jdbc;

    public PostgresPpskUserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String create(String tenantId, String agentId, String securityObject, String userGroup,
                         String username, String pskRef, Integer userProfileAttr, Integer vlanId,
                         String scheduleName, List<String> macBindings) {
        setTenant(tenantId);
        String id = "ppsk-" + UUID.randomUUID();
        jdbc.update("insert into ppsk_user (ppsk_user_id, tenant_id, agent_id, security_object, user_group, "
                        + "username, psk_ref, user_profile_attr, vlan_id, schedule_name, mac_bindings, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active')",
                id, tenantId, agentId, securityObject, userGroup, username, pskRef, userProfileAttr, vlanId,
                scheduleName, joinMacs(macBindings));
        return id;
    }

    @Override
    @Transactional
    public void markRotated(String tenantId, String id, String pskRef) {
        setTenant(tenantId);
        jdbc.update("update ppsk_user set psk_ref = ?, rotated_at = now() where ppsk_user_id = ?", pskRef, id);
    }

    @Override
    @Transactional
    public void revoke(String tenantId, String id) {
        setTenant(tenantId);
        jdbc.update("update ppsk_user set status = 'revoked' where ppsk_user_id = ?", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PpskUser> list(String tenantId, String agentId) {
        setTenant(tenantId);
        return jdbc.query("select * from ppsk_user where agent_id = ? order by created_at desc", USER, agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PpskUser> get(String tenantId, String id) {
        setTenant(tenantId);
        return jdbc.query("select * from ppsk_user where ppsk_user_id = ?", USER, id).stream().findFirst();
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }

    private static String joinMacs(List<String> macs) {
        return macs == null || macs.isEmpty() ? null : String.join(",", macs);
    }

    private static List<String> splitMacs(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
