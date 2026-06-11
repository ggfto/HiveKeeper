package io.hivekeeper.core.tasks;

import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.drivers.Driver;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.drivers.HiveOsCapture;
import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.session.CommandRunner;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.transport.SshSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Captures running-config (and, separately, the TPM-backed PPSK/users channel) into a
 * {@link ConfigSnapshot}, then persists it via a {@link BackupStore}. The capture (produce) and the
 * persistence (store) are deliberately separate so the store can later move on-prem or to a remote
 * backend without touching capture logic.
 */
public final class BackupService {

    private final DriverRegistry drivers;
    private final BackupStore store;
    private final Clock clock;

    public BackupService(DriverRegistry drivers, BackupStore store, Clock clock) {
        this.drivers = drivers;
        this.store = store;
        this.clock = clock;
    }

    /** The produced snapshot plus where it was stored. */
    public record Outcome(ConfigSnapshot snapshot, BackupRef ref, boolean usersIncluded, int configBytes) {
    }

    public Outcome backup(UUID commandId, DeviceRef ref, boolean includeUsers, boolean includeSecrets,
                          SshSession session, EventSink sink) throws IOException {
        CommandRunner runner = new CommandRunner(session);

        sink.emit(new Event.Progress(commandId, ref.id(), "Fingerprinting device", 10));
        String version = runner.run("show version");
        Driver driver = drivers.select(version)
                .orElseThrow(() -> new IOException("No driver recognizes this device from 'show version'"));

        sink.emit(new Event.Progress(commandId, ref.id(), "Capturing running-config", 40));
        String running = runner.run(driver.runningConfigCommand(includeSecrets));

        String users = null;
        if (includeUsers) {
            sink.emit(new Event.Progress(commandId, ref.id(), "Capturing PPSK users (separate channel)", 65));
            try {
                users = runner.run(driver.usersConfigCommand());
            } catch (IOException e) {
                sink.emit(new Event.Log(commandId, ref.id(), "users channel unavailable: " + e.getMessage()));
            }
        }

        String firmware = driver.parseDevice(ref.id(), new HiveOsCapture(version, "", "")).firmwareVersion();
        ConfigSnapshot snapshot = new ConfigSnapshot(ref.id(), running, users, firmware, Instant.now(clock));

        sink.emit(new Event.Progress(commandId, ref.id(), "Persisting backup", 90));
        BackupRef stored = store.write(snapshot);

        int bytes = running == null ? 0 : running.getBytes(StandardCharsets.UTF_8).length;
        return new Outcome(snapshot, stored, users != null, bytes);
    }
}
