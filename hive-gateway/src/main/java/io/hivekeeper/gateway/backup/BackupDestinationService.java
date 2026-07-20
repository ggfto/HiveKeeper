package io.hivekeeper.gateway.backup;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for an organization's backup destination — the git repository every agent in the org pushes
 * its config history to.
 *
 * <p>Unlike {@link io.hivekeeper.gateway.ppsk.PpskUserService}, this DOES hold the secret, encrypted at rest
 * with {@code HIVEKEEPER_CRYPTO_KEY}. That is deliberate and it is a real trade. A PPSK is minted once for
 * one agent and can be forgotten immediately; a backup destination has to reach every agent in the
 * organization, including one that was offline when it was set and one enrolled next month. Without the
 * gateway being able to re-seal it, the normal case becomes an agent that silently backs up nowhere.
 *
 * <p>So: whoever holds both the database and the crypto key can read the token. Scope the token to the
 * backup repository and nothing else. Implementations must never return it to a browser.
 */
public interface BackupDestinationService {

    /** The stored destination. {@code token} is the decrypted secret and must not leave the gateway. */
    record Destination(String repoUrl, String branch, String username, String token, Instant updatedAt,
                       String updatedBy) {

        /** Never log the token. */
        @Override
        public String toString() {
            return "Destination[repoUrl=" + repoUrl + ", branch=" + branch + ", token=***]";
        }
    }

    /** Creates or replaces the destination for a tenant. */
    void save(String tenantId, String repoUrl, String branch, String username, String token, String updatedBy);

    /** The destination for a tenant, or empty when none is configured. */
    Optional<Destination> get(String tenantId);

    /** Removes the destination; agents told about it fall back to local-only backups. */
    void clear(String tenantId);
}
