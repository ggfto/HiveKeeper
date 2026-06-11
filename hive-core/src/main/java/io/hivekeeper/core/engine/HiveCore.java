package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.transport.SshTransport;
import io.hivekeeper.core.transport.SshjTransport;
import java.time.Clock;

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
        SshTransport transport = new SshjTransport();
        DriverRegistry drivers = DriverRegistry.fromServiceLoader();
        return new LocalEngine(transport, credentials, drivers, backupStore, Clock.systemUTC());
    }
}
