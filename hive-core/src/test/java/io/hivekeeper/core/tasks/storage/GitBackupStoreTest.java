package io.hivekeeper.core.tasks.storage;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.DeviceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitBackupStoreTest {

    @Test
    void writesFilesAndCommits(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        GitBackupStore store = new GitBackupStore(repo);

        ConfigSnapshot snapshot = new ConfigSnapshot(
                DeviceId.of("ap230-lab-1"),
                "hostname ap230-lab-1\nssid LabWifi\n",
                "ppsk-user demo password ***\n",
                "10.0r7a",
                Instant.parse("2026-06-11T00:00:00Z"));

        BackupRef ref = store.write(snapshot);

        assertEquals("git", ref.storeId());
        assertFalse(ref.commitId().isBlank());

        Path running = repo.resolve("ap230-lab-1").resolve("running-config.txt");
        Path users = repo.resolve("ap230-lab-1").resolve("users.txt");
        assertTrue(Files.exists(running));
        assertTrue(Files.exists(users));
        assertTrue(Files.readString(running).contains("LabWifi"));

        // A second backup appends to history with a distinct commit.
        BackupRef ref2 = store.write(snapshot);
        assertNotEquals(ref.commitId(), ref2.commitId());
    }

    @Test
    void omitsUsersFileWhenChannelAbsent(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        GitBackupStore store = new GitBackupStore(repo);

        ConfigSnapshot snapshot = new ConfigSnapshot(
                DeviceId.of("ap1"), "config\n", null, null,
                Instant.parse("2026-06-11T00:00:00Z"));

        store.write(snapshot);

        assertFalse(Files.exists(repo.resolve("ap1").resolve("users.txt")));
        assertTrue(Files.exists(repo.resolve("ap1").resolve("running-config.txt")));
    }
}
