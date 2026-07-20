package io.hivekeeper.gateway.backup;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The dev/demo backup-destination store: a process-local, tenant-scoped map so the {@code !postgres} stack
 * can exercise the UI without a database. Mirrors {@link PostgresBackupDestinationService}'s behaviour but
 * holds nothing across a restart — which is the right shape for a stack that also loses its fleet on restart.
 */
@Service
@Profile("!postgres")
public class InMemoryBackupDestinationService implements BackupDestinationService {

    private final Map<String, Destination> byTenant = new LinkedHashMap<>();

    @Override
    public synchronized void save(String tenantId, String repoUrl, String branch, String username, String token,
                                  String updatedBy) {
        byTenant.put(tenantId, new Destination(repoUrl, branch, username, token, Instant.now(), updatedBy));
    }

    @Override
    public synchronized Optional<Destination> get(String tenantId) {
        return Optional.ofNullable(byTenant.get(tenantId));
    }

    @Override
    public synchronized void clear(String tenantId) {
        byTenant.remove(tenantId);
    }
}
