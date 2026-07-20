package io.hivekeeper.core.tasks.storage;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.BackupRemote;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.DeviceId;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pushes against a bare repository on disk, reached over {@code file://}. Real JGit transport and real
 * ref updates, but no network and no credentials server — so the test exercises the code that matters
 * without depending on a forge being up.
 */
class GitBackupStorePushTest {

    private static ConfigSnapshot snapshot(String config) {
        return new ConfigSnapshot(DeviceId.of("192.168.68.115"), config, null, "10.6r6", Instant.now());
    }

    private static Path bareRepo(Path dir) throws Exception {
        Path bare = dir.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).call().close();
        return bare;
    }

    private static String urlOf(Path bare) {
        return bare.toUri().toString();
    }

    @Test
    void saysNothingAboutPushingWhenNoRemoteIsConfigured(@TempDir Path dir) throws Exception {
        GitBackupStore store = new GitBackupStore(dir.resolve("local"));

        BackupRef ref = store.write(snapshot("hostname ap1\n"));

        assertNotNull(ref.commitId());
        assertNull(ref.push(), "a local-only store has no push status to report");
    }

    @Test
    void pushesTheCommitToTheRemote(@TempDir Path dir) throws Exception {
        Path bare = bareRepo(dir);
        GitBackupStore store = new GitBackupStore(dir.resolve("local"),
                () -> new BackupRemote(urlOf(bare), null, "unused-for-file-transport", "main"));

        BackupRef ref = store.write(snapshot("hostname ap1\n"));

        assertTrue(ref.push().pushed(), "push reported: " + ref.push().error());
        assertEquals(0, ref.push().pendingCommits());
        try (Git remote = Git.open(bare.toFile())) {
            assertNotNull(remote.getRepository().resolve("refs/heads/main"),
                    "the branch should exist on the remote");
        }
    }

    @Test
    void keepsTheCommitWhenTheRemoteIsUnreachable(@TempDir Path dir) throws Exception {
        // The whole point: a backup must not be lost because the destination was down.
        GitBackupStore store = new GitBackupStore(dir.resolve("local"),
                () -> BackupRemote.of(dir.resolve("does-not-exist.git").toUri().toString(), "t"));

        BackupRef ref = store.write(snapshot("hostname ap1\n"));

        assertNotNull(ref.commitId(), "the commit must still have been made");
        assertFalse(ref.push().pushed());
        assertNotNull(ref.push().error());
        assertEquals(1, ref.push().pendingCommits(), "one commit is waiting to go out");
    }

    @Test
    void catchesUpOnPendingCommitsOnceTheRemoteComesBack(@TempDir Path dir) throws Exception {
        Path bare = bareRepo(dir);
        Path missing = dir.resolve("gone.git");
        AtomicReference<String> url = new AtomicReference<>(missing.toUri().toString());
        GitBackupStore store = new GitBackupStore(dir.resolve("local"),
                () -> BackupRemote.of(url.get(), "t"));

        BackupRef first = store.write(snapshot("hostname ap1\n"));
        BackupRef second = store.write(snapshot("hostname ap2\n"));
        assertFalse(first.push().pushed());
        assertEquals(2, second.push().pendingCommits());

        // The destination is fixed; the next backup pushes the whole backlog, not just its own commit.
        url.set(urlOf(bare));
        BackupRef third = store.write(snapshot("hostname ap3\n"));

        assertTrue(third.push().pushed(), "push reported: " + third.push().error());
        try (Git remote = Git.open(bare.toFile())) {
            int commits = 0;
            for (var ignored : remote.log().add(remote.getRepository().resolve("refs/heads/main")).call()) {
                commits++;
            }
            assertEquals(3, commits, "every pending commit should have gone out, not only the newest");
        }
    }

    @Test
    void pushesAnUnchangedCaptureToClearABacklog(@TempDir Path dir) throws Exception {
        Path bare = bareRepo(dir);
        Path missing = dir.resolve("gone.git");
        AtomicReference<String> url = new AtomicReference<>(missing.toUri().toString());
        GitBackupStore store = new GitBackupStore(dir.resolve("local"),
                () -> BackupRemote.of(url.get(), "t"));

        BackupRef first = store.write(snapshot("hostname ap1\n"));
        assertFalse(first.push().pushed());

        // Same config: no new commit. A store that skipped the push here would never catch up on a fleet
        // whose configuration has stopped changing — which is most of a fleet, most of the time.
        url.set(urlOf(bare));
        BackupRef unchanged = store.write(snapshot("hostname ap1\n"));

        assertEquals(first.commitId(), unchanged.commitId(), "an unchanged capture makes no new commit");
        assertTrue(unchanged.push().pushed(), "but it must still flush what was pending");
    }

    @Test
    void followsTheDestinationWhenItChanges(@TempDir Path dir) throws Exception {
        Path first = bareRepo(dir);
        Path second = dir.resolve("second.git");
        Git.init().setBare(true).setDirectory(second.toFile()).call().close();
        AtomicReference<String> url = new AtomicReference<>(urlOf(first));
        GitBackupStore store = new GitBackupStore(dir.resolve("local"),
                () -> BackupRemote.of(url.get(), "t"));

        store.write(snapshot("hostname ap1\n"));
        // Re-pointed remotely while the agent runs; the next backup must follow without a restart.
        url.set(second.toUri().toString());
        BackupRef ref = store.write(snapshot("hostname ap2\n"));

        assertTrue(ref.push().pushed(), "push reported: " + ref.push().error());
        try (Git remote = Git.open(second.toFile())) {
            assertNotNull(remote.getRepository().resolve("refs/heads/main"));
        }
    }

    @Test
    void neverPutsTheTokenInARemoteDescription() {
        BackupRemote remote = new BackupRemote("https://example.org/x.git", null, "ghp_supersecret", null);

        assertFalse(remote.toString().contains("ghp_supersecret"), "toString must not leak the token");
        assertEquals(BackupRemote.DEFAULT_BRANCH, remote.branch());
        assertEquals(BackupRemote.DEFAULT_USERNAME, remote.username());
    }
}
