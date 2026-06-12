package io.hivekeeper.gateway;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * Records and reads the per-tenant audit of operations dispatched to agents. Every access sets the
 * transaction-local tenant context ({@code app.current_tenant}) that the RLS policy on
 * {@code operation_log} keys off — so the database itself, not application code, enforces that one
 * tenant can never read or write another tenant's rows. Only present under the {@code postgres} profile.
 */
@Service
@Profile("postgres")
public class OperationLog {

    public record Entry(long id, String tenantId, String agentId, String opType, String host,
                        String summary, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public OperationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void record(String tenantId, String agentId, String opType, String host, String summary) {
        setTenantContext(tenantId);
        jdbc.update("insert into operation_log (tenant_id, agent_id, op_type, host, summary) values (?, ?, ?, ?, ?)",
                tenantId, agentId, opType, host, summary);
    }

    @Transactional(readOnly = true)
    public List<Entry> list(String tenantId) {
        setTenantContext(tenantId);
        return jdbc.query(
                "select id, tenant_id, agent_id, op_type, host, summary, created_at "
                        + "from operation_log order by created_at desc limit 100",
                (rs, n) -> new Entry(rs.getLong("id"), rs.getString("tenant_id"), rs.getString("agent_id"),
                        rs.getString("op_type"), rs.getString("host"), rs.getString("summary"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** Sets the transaction-local tenant context the RLS policy reads. {@code true} = local to this txn. */
    private void setTenantContext(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }
}
