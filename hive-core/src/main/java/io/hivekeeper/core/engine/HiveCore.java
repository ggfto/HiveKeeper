package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.discovery.Scanner;
import io.hivekeeper.core.discovery.TcpBannerScanner;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.SecretUnsealer;
import io.hivekeeper.core.spi.WritableCredentialProvider;
import io.hivekeeper.core.transport.SshTransport;
import io.hivekeeper.core.transport.SshjTransport;

/**
 * Composition root: assembles a ready-to-use local {@link Engine} from its on-prem collaborators.
 * Front-ends call this once and then speak only to {@link Engine} + DTOs — they never touch transport,
 * session, or driver internals.
 */
public final class HiveCore {

    private HiveCore() {
    }

    /** Build a local engine with the default sshj transport and ServiceLoader-discovered drivers. */
    public static Engine localEngine(CredentialProvider credentials, BackupStore backupStore) {
        return localEngine(credentials, backupStore, null, null);
    }

    /**
     * Build a local engine that can also manage credentials. Pass a {@code writableCredentials} +
     * {@code unsealer} (the on-prem agent does) to enable {@link io.hivekeeper.core.api.Command.SetCredential};
     * pass {@code null} for both to leave it disabled.
     */
    public static Engine localEngine(CredentialProvider credentials, BackupStore backupStore,
                                     WritableCredentialProvider writableCredentials, SecretUnsealer unsealer) {
        SshTransport transport = new SshjTransport();
        DriverRegistry drivers = DriverRegistry.fromServiceLoader();
        Scanner scanner = new TcpBannerScanner();
        return new LocalEngine(transport, credentials, drivers, backupStore, scanner, writableCredentials, unsealer);
    }
}
