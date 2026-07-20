package io.hivekeeper.core.model;

/**
 * Where a {@link io.hivekeeper.core.spi.BackupStore} pushes its commits, and how it authenticates.
 *
 * <p>The token is a live secret. It reaches the agent sealed to the agent's own key and is held encrypted
 * at rest, exactly like an AP credential — so this record is short-lived, built when a push is about to
 * happen and never written anywhere. It is deliberately not part of {@link BackupRef}, which is metadata
 * that travels back to the cloud.
 *
 * <p>{@code username} exists because forges disagree: GitHub and Gitea accept any non-empty username with
 * a token as the password, GitLab wants a literal {@code oauth2}. Defaulting it rather than demanding it
 * keeps the common case to two fields.
 */
public record BackupRemote(String url, String username, String token, String branch) {

    /** What most forges accept when the password is a personal access token. */
    public static final String DEFAULT_USERNAME = "hivekeeper";
    public static final String DEFAULT_BRANCH = "main";

    public BackupRemote {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("backup remote URL required");
        }
        username = (username == null || username.isBlank()) ? DEFAULT_USERNAME : username.trim();
        branch = (branch == null || branch.isBlank()) ? DEFAULT_BRANCH : branch.trim();
        url = url.trim();
    }

    public static BackupRemote of(String url, String token) {
        return new BackupRemote(url, null, token, null);
    }

    /** Never log the token. */
    @Override
    public String toString() {
        return "BackupRemote[url=" + url + ", username=" + username + ", branch=" + branch + ", token=***]";
    }
}
