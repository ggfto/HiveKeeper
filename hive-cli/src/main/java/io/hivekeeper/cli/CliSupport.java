package io.hivekeeper.cli;

import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import java.util.Optional;

/** Small helpers shared by the CLI subcommands. */
final class CliSupport {

    private CliSupport() {
    }

    /** A credential provider that returns the same flag/env-derived credentials for any device. */
    static CredentialProvider credentials(ConnectionOptions conn) {
        Credentials creds = new Credentials(conn.user, conn.resolvePassword());
        return deviceId -> Optional.of(creds);
    }

    /** A backup store that refuses to be used — for commands that never persist anything. */
    static final BackupStore NO_STORE = snapshot -> {
        throw new UnsupportedOperationException("backup store not configured for this command");
    };

    static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
