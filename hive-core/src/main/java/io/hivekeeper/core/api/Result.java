package io.hivekeeper.core.api;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import java.util.UUID;

/** The terminal outcome of a {@link Command}. Echoes commandId + deviceId for correlation. */
public sealed interface Result permits Result.Inventory, Result.Backup {

    UUID commandId();

    DeviceId deviceId();

    record Inventory(UUID commandId, DeviceId deviceId, Device device) implements Result {
    }

    record Backup(UUID commandId, DeviceId deviceId, BackupRef ref, boolean usersIncluded, int configBytes)
            implements Result {
    }
}
