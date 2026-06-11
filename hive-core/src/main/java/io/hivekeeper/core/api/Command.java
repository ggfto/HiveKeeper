package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceRef;
import java.util.List;
import java.util.UUID;

/**
 * A unit of work to run against a device. Serializable and carries no live handles — it references the
 * target by {@link DeviceRef}, never an open session. This is the exact value the CLI builds from argv
 * and (later) the value a cloud control plane serializes into a job for an agent.
 */
public sealed interface Command permits Command.Inventory, Command.BackupConfig, Command.RunRaw {

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

    /** Run a list of raw CLI commands and return their verbatim output. Used to build golden fixtures
     *  and as the basis of a future terminal/passthrough feature. */
    record RunRaw(UUID commandId, DeviceRef device, List<String> commands) implements Command {

        public RunRaw {
            commands = List.copyOf(commands);
        }

        public static RunRaw of(DeviceRef device, List<String> commands) {
            return new RunRaw(UUID.randomUUID(), device, commands);
        }
    }
}
