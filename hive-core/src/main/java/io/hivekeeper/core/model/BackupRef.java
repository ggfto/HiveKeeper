package io.hivekeeper.core.model;

/** A pointer to a persisted backup (e.g. a git commit). Metadata only — no secret payload. */
public record BackupRef(String storeId, String commitId, String path) {
}
