package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceRef;
import java.util.UUID;

/**
 * A unit of work to run against a device. Serializable and carries no live handles — it references the
 * target by {@link DeviceRef}, never an open session. This is the exact value the CLI builds from argv
 * and (later) the value a cloud control plane serializes into a job for an agent.
 */
public sealed interface Command permits Command.Inventory, Command.BackupConfig {

    UUID commandId();

    DeviceRef device();

    /** Collect an inventory snapshot (show version / interface / station). */
    record Inventory(UUID commandId, DeviceRef device) implements Command {
        public static Inventory of(DeviceRef device) {
            return new Inventory(UUID.randomUUID(), device);
        }
    }

    /** Capture running-config (and, optionally, the separate PPSK/users channel) and persist it. */
    record BackupConfig(UUID commandId, DeviceRef device, boolean includeUsers, boolean includeSecrets)
            implements Command {

        public static BackupConfig of(DeviceRef device) {
            return new BackupConfig(UUID.randomUUID(), device, true, true);
        }
    }
}
