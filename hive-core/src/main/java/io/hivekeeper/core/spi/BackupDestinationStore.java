package io.hivekeeper.core.spi;

import io.hivekeeper.core.model.BackupRemote;

/**
 * Where the agent keeps the backup destination it was given remotely — the repository URL, the branch, and
 * the token — so it survives a restart without anyone touching the machine.
 *
 * <p>The token is a live secret and belongs at rest under the agent's own vault key, the same way an AP
 * credential does. Implementations must never write it in the clear without saying so loudly.
 *
 * <p>Deliberately a store and not a config value: {@code agent.conf} is read-only by design (env var, then
 * file, then default, loaded once at startup), and a destination that can be changed from the console is
 * mutable state, not configuration.
 */
public interface BackupDestinationStore {

    /** The current destination, or null when none has been configured. */
    BackupRemote get();

    /** Replaces the destination and persists it. Passing null clears it, returning the agent to local-only. */
    void set(BackupRemote remote);
}
