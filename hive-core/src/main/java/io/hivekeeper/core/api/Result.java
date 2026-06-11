package io.hivekeeper.core.api;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import java.util.Map;
import java.util.UUID;

/** The terminal outcome of a {@link Command}. Echoes commandId + deviceId for correlation. */
public sealed interface Result permits Result.Inventory, Result.Backup, Result.RawCapture {

    UUID commandId();

    DeviceId deviceId();

    record Inventory(UUID commandId, DeviceId deviceId, Device device) implements Result {
    }

    record Backup(UUID commandId, DeviceId deviceId, BackupRef ref, boolean usersIncluded, int configBytes)
            implements Result {
    }

    /** Verbatim output keyed by the command that produced it (insertion-ordered). */
    record RawCapture(UUID commandId, DeviceId deviceId, Map<String, String> outputs) implements Result {
    }
}
