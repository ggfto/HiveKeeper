package io.hivekeeper.server;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import java.util.Optional;

/** Pure mapping helpers from a {@link ConnectionRequest} to engine inputs (unit-testable, no I/O). */
final class ServerSupport {

    static final String DEFAULT_USER = "admin";
    static final String DEFAULT_DIR = "hivekeeper-backups";

    private ServerSupport() {
    }

    static DeviceRef deviceRef(ConnectionRequest req) {
        if (req == null || req.host() == null || req.host().isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        int port = req.port() == null ? 22 : req.port();
        return DeviceRef.ssh(req.host().trim(), port);
    }

    static CredentialProvider credentials(ConnectionRequest req) {
        String user = req.user() == null || req.user().isBlank() ? DEFAULT_USER : req.user();
        Credentials creds = new Credentials(user, req.password() == null ? "" : req.password());
        return id -> Optional.of(creds);
    }

    static String backupDir(ConnectionRequest req) {
        return req.dir() == null || req.dir().isBlank() ? DEFAULT_DIR : req.dir();
    }

    /** A backup store that refuses use — for operations (like inventory) that never persist. */
    static final BackupStore NO_OP_STORE = snapshot -> {
        throw new UnsupportedOperationException("no backup store for this operation");
    };
}
