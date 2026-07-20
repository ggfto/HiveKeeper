package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.discovery.Scanner;
import io.hivekeeper.core.discovery.TcpBannerScanner;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.BackupDestinationStore;
import io.hivekeeper.core.spi.PpskUserStore;
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
        return localEngine(credentials, backupStore, writableCredentials, unsealer, null);
    }

    /**
     * Build a local engine that can also manage credentials and PPSK users. Pass a {@code ppskUsers} store
     * (+ {@code unsealer}) to enable {@link io.hivekeeper.core.api.Command.ManagePpskUser} (the on-prem agent
     * does); pass {@code null} to leave PPSK key management disabled.
     */
    public static Engine localEngine(CredentialProvider credentials, BackupStore backupStore,
                                     WritableCredentialProvider writableCredentials, SecretUnsealer unsealer,
                                     PpskUserStore ppskUsers) {
        return localEngine(new SshjTransport(), credentials, backupStore, writableCredentials, unsealer, ppskUsers);
    }

    /**
     * Build a local engine over a caller-supplied {@link SshTransport}. The agent uses this to choose the SSH
     * host-key policy (TOFU / strict / accept-all) and its managed {@code known_hosts} path; other front-ends
     * use the {@code SshjTransport} default (TOFU against the per-user managed store).
     */
    public static Engine localEngine(SshTransport transport, CredentialProvider credentials, BackupStore backupStore,
                                     WritableCredentialProvider writableCredentials, SecretUnsealer unsealer,
                                     PpskUserStore ppskUsers) {
        return localEngine(transport, credentials, backupStore, writableCredentials, unsealer, ppskUsers, null);
    }

    /**
     * The full form. {@code backupDestinations} enables configuring the backup repository from the console;
     * only the on-prem agent passes one.
     */
    public static Engine localEngine(SshTransport transport, CredentialProvider credentials, BackupStore backupStore,
                                     WritableCredentialProvider writableCredentials, SecretUnsealer unsealer,
                                     PpskUserStore ppskUsers, BackupDestinationStore backupDestinations) {
        DriverRegistry drivers = DriverRegistry.fromServiceLoader();
        Scanner scanner = new TcpBannerScanner();
        return new LocalEngine(transport, credentials, drivers, backupStore, scanner, writableCredentials,
                unsealer, ppskUsers, backupDestinations);
    }
}
