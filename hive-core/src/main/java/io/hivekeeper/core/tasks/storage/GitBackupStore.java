package io.hivekeeper.core.tasks.storage;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.spi.BackupStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists config snapshots into a local git repository — one directory per device, one commit per
 * capture. This gives diff / history / rollback for free, which is the single most valuable property
 * of a homelab backup tool. The only {@link BackupStore} implementation in v0.1.
 *
 * <p>v0.1 commits every run (including unchanged captures) so each backup leaves an audit record;
 * de-duplicating identical snapshots is a later refinement.
 */
public final class GitBackupStore implements BackupStore {

    private static final String STORE_ID = "git";

    private final Path root;

    public GitBackupStore(Path root) {
        this.root = root;
    }

    @Override
    public BackupRef write(ConfigSnapshot snapshot) throws IOException {
        Files.createDirectories(root);
        try (Git git = openOrInit()) {
            String dirName = sanitize(snapshot.deviceId().value());
            Path deviceDir = root.resolve(dirName);
            Files.createDirectories(deviceDir);

            Path runningFile = deviceDir.resolve("running-config.txt");
            Files.writeString(runningFile, nullToEmpty(snapshot.runningConfig()), StandardCharsets.UTF_8);

            if (snapshot.usersConfig() != null) {
                Path usersFile = deviceDir.resolve("users.txt");
                Files.writeString(usersFile, snapshot.usersConfig(), StandardCharsets.UTF_8);
            }

            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit()
                    .setAllowEmpty(true)
                    .setMessage("backup " + snapshot.deviceId().value() + " @ " + snapshot.capturedAt())
                    .setAuthor("hivekeeper", "hivekeeper@localhost")
                    .setCommitter("hivekeeper", "hivekeeper@localhost")
                    .call();

            String relPath = root.relativize(runningFile).toString().replace('\\', '/');
            return new BackupRef(STORE_ID, commit.getName(), relPath);
        } catch (GitAPIException e) {
            throw new IOException("git backup failed: " + e.getMessage(), e);
        }
    }

    private Git openOrInit() throws IOException {
        if (Files.isDirectory(root.resolve(".git"))) {
            return Git.open(root.toFile());
        }
        try {
            return Git.init().setDirectory(root.toFile()).call();
        } catch (GitAPIException e) {
            throw new IOException("git init failed: " + e.getMessage(), e);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
