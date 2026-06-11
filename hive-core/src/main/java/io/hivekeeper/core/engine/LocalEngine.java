package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.tasks.BackupService;
import io.hivekeeper.core.tasks.InventoryService;
import io.hivekeeper.core.transport.SshSession;
import io.hivekeeper.core.transport.SshTransport;
import java.time.Clock;
import java.util.UUID;

/**
 * The only {@link Engine} implementation in v0.1: runs everything in-process against a
 * directly-reachable device. It is tenant-unaware and stateless. A future RemoteEngine will implement
 * the same interface over an agent channel — callers never learn which one they hold.
 */
public final class LocalEngine implements Engine {

    private final SshTransport transport;
    private final CredentialProvider credentials;
    private final InventoryService inventory;
    private final BackupService backup;

    public LocalEngine(SshTransport transport, CredentialProvider credentials,
                       DriverRegistry drivers, BackupStore backupStore, Clock clock) {
        this.transport = transport;
        this.credentials = credentials;
        this.inventory = new InventoryService(drivers);
        this.backup = new BackupService(drivers, backupStore, clock);
    }

    @Override
    public Result execute(Command command, EventSink sink) throws HiveException {
        UUID id = command.commandId();
        DeviceRef ref = command.device();
        DeviceId deviceId = ref.id();

        sink.emit(new Event.Started(id, deviceId, command.getClass().getSimpleName()));
        try {
            Credentials creds = credentials.resolve(deviceId)
                    .orElseThrow(() -> new IllegalStateException("No credentials configured for " + deviceId));

            try (SshSession session = transport.open(ref, creds)) {
                Result result = switch (command) {
                    case Command.Inventory ignored -> {
                        Device device = inventory.collect(id, ref, session, sink);
                        yield new Result.Inventory(id, deviceId, device);
                    }
                    case Command.BackupConfig c -> {
                        BackupService.Outcome outcome =
                                backup.backup(id, ref, c.includeUsers(), c.includeSecrets(), session, sink);
                        yield new Result.Backup(id, deviceId, outcome.ref(),
                                outcome.usersIncluded(), outcome.configBytes());
                    }
                };
                sink.emit(new Event.Completed(id, deviceId, result));
                return result;
            }
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            sink.emit(new Event.Failed(id, deviceId, e.getClass().getSimpleName(), detail));
            throw new HiveException(id, deviceId, "execute failed: " + detail, e);
        }
    }
}
