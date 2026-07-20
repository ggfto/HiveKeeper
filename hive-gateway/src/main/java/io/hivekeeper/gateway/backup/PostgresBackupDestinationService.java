package io.hivekeeper.gateway.backup;

import io.hivekeeper.core.crypto.SecretCipher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The real backup-destination store: shared-schema Postgres with row-level security as the hard wall between
 * organizations (every method sets {@code app.current_tenant} first, exactly like the other services).
 *
 * <p>The token is encrypted with {@link SecretCipher} before it is written and decrypted only when the
 * gateway is about to re-seal it to an agent. See {@link BackupDestinationService} for why this one secret
 * is persisted at all.
 */
@Slf4j
@Service
@Profile("postgres")
public class PostgresBackupDestinationService implements BackupDestinationService {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public PostgresBackupDestinationService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void save(String tenantId, String repoUrl, String branch, String username, String token,
                     String updatedBy) {
        setTenant(tenantId);
        jdbc.update("insert into backup_destination (tenant_id, repo_url, branch, username, token_enc, "
                        + "updated_at, updated_by) values (?, ?, ?, ?, ?, now(), ?) "
                        + "on conflict (tenant_id) do update set repo_url = excluded.repo_url, "
                        + "branch = excluded.branch, username = excluded.username, "
                        + "token_enc = excluded.token_enc, updated_at = now(), updated_by = excluded.updated_by",
                tenantId, repoUrl, branch, username, cipher.encrypt(token), updatedBy);
    }

    @Override
    @Transactional
    public Optional<Destination> get(String tenantId) {
        setTenant(tenantId);
        return jdbc.query("select * from backup_destination where tenant_id = ?", (rs, n) -> {
            String stored = rs.getString("token_enc");
            String token;
            try {
                token = cipher.decrypt(stored);
            } catch (RuntimeException e) {
                // The crypto key changed. Surfacing this as "no destination" would read as "never configured";
                // it is not the same thing, and the operator has to re-enter the token to fix it.
                log.warn("cannot decrypt the backup destination token for tenant {} — was HIVEKEEPER_CRYPTO_KEY "
                        + "rotated? The destination must be re-entered.", tenantId);
                return null;
            }
            return new Destination(rs.getString("repo_url"), rs.getString("branch"), rs.getString("username"),
                    token, instant(rs.getTimestamp("updated_at")), rs.getString("updated_by"));
        }, tenantId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    @Transactional
    public void clear(String tenantId) {
        setTenant(tenantId);
        jdbc.update("delete from backup_destination where tenant_id = ?", tenantId);
    }

    private void setTenant(String tenantId) {
        jdbc.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenantId);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
