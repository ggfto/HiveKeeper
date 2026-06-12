package io.hivekeeper.gateway;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable job persistence. Every access sets the transaction-local tenant context that the RLS policy
 * on {@code job} keys off, so the database enforces tenant isolation. Only present under {@code postgres}.
 */
@Service
@Profile("postgres")
public class JobService {

    public record JobRow(String jobId, String tenantId, String agentId, String idempotencyKey, String type,
                         String commandJson, String status, String resultJson, String error) {
    }

    private static final RowMapper<JobRow> ROW = (rs, n) -> new JobRow(
            rs.getString("job_id"), rs.getString("tenant_id"), rs.getString("agent_id"),
            rs.getString("idempotency_key"), rs.getString("type"), rs.getString("command_json"),
            rs.getString("status"), rs.getString("result_json"), rs.getString("error"));

    private final JdbcTemplate jdbc;

    public JobService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String create(String tenantId, String agentId, String idempotencyKey, String type, String commandJson) {
        setTenant(tenantId);
        String jobId = UUID.randomUUID().toString();
        jdbc.update("insert into job (job_id, tenant_id, agent_id, idempotency_key, type, command_json, status) "
                        + "values (?, ?, ?, ?, ?, ?, 'PENDING')",
                jobId, tenantId, agentId, idempotencyKey, type, commandJson);
        return jobId;
    }

    @Transactional
    public void markDispatched(String tenantId, String jobId) {
        setTenant(tenantId);
        jdbc.update("update job set status = 'DISPATCHED', updated_at = now() "
                + "where job_id = ? and status = 'PENDING'", jobId);
    }

    @Transactional
    public void complete(String tenantId, String jobId, boolean ok, String resultJson, String error) {
        setTenant(tenantId);
        jdbc.update("update job set status = ?, result_json = ?, error = ?, updated_at = now() "
                        + "where job_id = ? and status in ('PENDING', 'DISPATCHED')",
                ok ? "SUCCEEDED" : "FAILED", resultJson, error, jobId);
    }

    @Transactional(readOnly = true)
    public List<JobRow> pendingFor(String tenantId, String agentId) {
        setTenant(tenantId);
        return jdbc.query("select * from job where agent_id = ? and status in ('PENDING', 'DISPATCHED') "
                + "order by created_at", ROW, agentId);
    }

    @Transactional(readOnly = true)
    public Optional<JobRow> get(String tenantId, String jobId) {
        setTenant(tenantId);
        List<JobRow> rows = jdbc.query("select * from job where job_id = ?", ROW, jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }
}
