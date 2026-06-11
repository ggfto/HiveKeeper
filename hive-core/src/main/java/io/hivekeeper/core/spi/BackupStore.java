package io.hivekeeper.core.spi;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import java.io.IOException;

/**
 * Persists a captured config snapshot and returns a reference to it. The v0.1 implementation is a git
 * repository (GitBackupStore). This interface is the "persist" half of the deliberate produce/persist
 * split: tasks produce a {@link ConfigSnapshot} and hand it here — they never touch the filesystem
 * directly. Swapping in an object-store or remote backend later is additive.
 */
public interface BackupStore {
    BackupRef write(ConfigSnapshot snapshot) throws IOException;
}
