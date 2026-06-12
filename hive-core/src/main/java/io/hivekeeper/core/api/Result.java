package io.hivekeeper.core.api;

import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DiscoveryResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The terminal outcome of a {@link Command}. Echoes commandId + deviceId for correlation (for a
 *  network-scoped result the deviceId is the scan scope, e.g. the CIDR). */
public sealed interface Result
        permits Result.Inventory, Result.Backup, Result.RawCapture, Result.Discovered, Result.ConfigApplied {

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

    /** Reachable hosts found by a {@link Command.Discover} sweep. */
    record Discovered(UUID commandId, DeviceId deviceId, List<DiscoveryResult> hosts) implements Result {
        public Discovered {
            hosts = List.copyOf(hosts);
        }
    }

    /** Outcome of a configuration write: the lines applied, their verbatim CLI output, and whether the
     *  change was persisted with {@code save config}. */
    record ConfigApplied(UUID commandId, DeviceId deviceId, List<String> commands, List<String> outputs,
                         boolean saved) implements Result {
        public ConfigApplied {
            commands = List.copyOf(commands);
            outputs = List.copyOf(outputs);
        }
    }
}
