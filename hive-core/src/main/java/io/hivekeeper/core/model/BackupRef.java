package io.hivekeeper.core.model;

/** A pointer to a persisted backup (e.g. a git commit). Metadata only — no secret payload. */
public record BackupRef(String storeId, String commitId, String path, PushStatus push) {

    /** A local-only backup: no remote is configured, so there is nothing to say about pushing. */
    public BackupRef(String storeId, String commitId, String path) {
        this(storeId, commitId, path, null);
    }

    /**
     * What became of the attempt to push this commit off the agent.
     *
     * <p>Carried alongside the commit rather than thrown, because a failed push is not a failed backup.
     * The local commit is the rollback path and is already useful on its own; losing a snapshot because
     * the network blinked would be the worse outcome. So the capture succeeds, this reports what happened,
     * and {@code pendingCommits} says how far behind the remote has fallen — which is the number that
     * turns "one push failed" into "nothing has left this machine in three days".
     *
     * <p>{@code error} is null on success. It is a message, never the token.
     */
    public record PushStatus(boolean pushed, String error, int pendingCommits) {

        public static PushStatus ok() {
            return new PushStatus(true, null, 0);
        }

        public static PushStatus failed(String error, int pendingCommits) {
            return new PushStatus(false, error, pendingCommits);
        }
    }
}
