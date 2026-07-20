package io.hivekeeper.core.tasks.storage;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.BackupRemote;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.spi.BackupStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Persists config snapshots into a git repository — one directory per device, one commit per capture.
 * This gives diff / history / rollback for free, which is the single most valuable property of a backup
 * tool. The only {@link BackupStore} implementation in v0.1.
 *
 * <p>Unchanged captures are de-duplicated: if a backup produces no diff against {@code HEAD}, no new
 * commit is made and the existing {@code HEAD} is returned — history stays meaningful.
 *
 * <p>Given a {@link BackupRemote} it also pushes, so the history survives the machine it was captured on.
 * <b>A failed push is not a failed backup.</b> The commit is made first and always; a push that cannot
 * happen — expired token, network down, forge unreachable — is reported on the returned
 * {@link BackupRef.PushStatus} and retried by the next capture, because git pushes every pending commit,
 * not just the newest. Failing the whole operation instead would mean a network blip costs you the
 * snapshot, which is exactly backwards.
 */
@Slf4j
public final class GitBackupStore implements BackupStore {

    private static final String STORE_ID = "git";
    private static final String REMOTE_NAME = "origin";

    private final Path root;
    private final Supplier<BackupRemote> remote;

    public GitBackupStore(Path root) {
        this(root, () -> null);
    }

    /**
     * @param remote supplies the current destination, or null for local-only. A supplier and not a value
     *               because the destination is configured remotely and can change while the agent runs —
     *               reading it per capture means a token rotation takes effect on the next backup rather
     *               than on the next restart.
     */
    public GitBackupStore(Path root, Supplier<BackupRemote> remote) {
        this.root = root;
        this.remote = remote == null ? () -> null : remote;
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
            String relPath = root.relativize(runningFile).toString().replace('\\', '/');

            Status status = git.status().call();
            ObjectId head = git.getRepository().resolve("HEAD");
            boolean unchanged = head != null
                    && status.getAdded().isEmpty() && status.getChanged().isEmpty()
                    && status.getModified().isEmpty() && status.getRemoved().isEmpty()
                    && status.getMissing().isEmpty();
            if (unchanged) {
                log.debug("backup for {} unchanged; reusing commit {}", snapshot.deviceId(), head.getName());
                // Still try to push: the capture produced nothing new, but an earlier commit may be waiting
                // because a previous push failed. Otherwise a fleet that has gone quiet never catches up.
                return new BackupRef(STORE_ID, head.getName(), relPath, push(git));
            }

            RevCommit commit = git.commit()
                    .setMessage("backup " + snapshot.deviceId().value() + " @ " + snapshot.capturedAt())
                    .setAuthor("hivekeeper", "hivekeeper@localhost")
                    .setCommitter("hivekeeper", "hivekeeper@localhost")
                    .call();
            log.debug("committed backup for {} -> {}", snapshot.deviceId(), commit.getName());
            return new BackupRef(STORE_ID, commit.getName(), relPath, push(git));
        } catch (GitAPIException e) {
            throw new IOException("git backup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pushes every pending commit to the configured remote. Returns null when no remote is configured,
     * and never throws — the commit already succeeded, and the caller decides what to do about the push.
     */
    private BackupRef.PushStatus push(Git git) {
        BackupRemote target = remote.get();
        if (target == null) {
            return null;
        }
        try {
            ensureRemote(git, target.url());
            String branch = git.getRepository().getBranch();
            Iterable<PushResult> results = git.push()
                    .setRemote(REMOTE_NAME)
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + target.branch()))
                    .setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(target.username(), target.token()))
                    .call();

            // JGit does not throw on a rejected ref — it reports per-ref status, so a non-OK update here
            // is a real failure that would otherwise pass silently as a successful push.
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        String detail = status + (update.getMessage() == null ? "" : ": " + update.getMessage());
                        log.warn("backup push rejected: {}", detail);
                        return BackupRef.PushStatus.failed(detail, pending(git, target.branch()));
                    }
                }
            }
            log.debug("pushed backups to {}", target.url());
            return BackupRef.PushStatus.ok();
        } catch (GitAPIException | IOException | URISyntaxException | RuntimeException e) {
            // Deliberately broad: a transport problem must not lose the snapshot that was just committed.
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("backup push failed ({}); the commit is safe locally and the next backup will retry",
                    detail);
            return BackupRef.PushStatus.failed(detail, pending(git, target.branch()));
        }
    }

    /** Points {@code origin} at the configured URL, adding or updating it as the destination changes. */
    private static void ensureRemote(Git git, String url) throws GitAPIException, URISyntaxException {
        boolean exists = git.remoteList().call().stream().anyMatch(r -> REMOTE_NAME.equals(r.getName()));
        if (exists) {
            git.remoteSetUrl().setRemoteName(REMOTE_NAME).setRemoteUri(new URIish(url)).call();
        } else {
            git.remoteAdd().setName(REMOTE_NAME).setUri(new URIish(url)).call();
        }
    }

    /**
     * How many local commits the remote has not seen. Best-effort: if the remote-tracking ref is missing
     * (nothing has ever been pushed) this counts the whole history, which is the honest answer.
     */
    private static int pending(Git git, String branch) {
        try {
            ObjectId local = git.getRepository().resolve("HEAD");
            if (local == null) {
                return 0;
            }
            ObjectId remoteHead = git.getRepository().resolve("refs/remotes/" + REMOTE_NAME + "/" + branch);
            int count = 0;
            for (RevCommit ignored : git.log().add(local).call()) {
                count++;
            }
            if (remoteHead == null) {
                return count;
            }
            int seen = 0;
            for (RevCommit ignored : git.log().add(remoteHead).call()) {
                seen++;
            }
            return Math.max(0, count - seen);
        } catch (GitAPIException | IOException | RuntimeException e) {
            log.debug("could not count pending commits: {}", e.toString());
            return 0;
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
