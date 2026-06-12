package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.SsidSpec;
import java.util.List;
import java.util.UUID;

/**
 * A unit of work. Serializable and carrying no live handles. Most commands are device-scoped (they
 * reference a target by {@link DeviceRef}, never an open session); {@link Discover} is network-scoped,
 * which is why {@code device()} lives on the device commands rather than on this base interface.
 * This is the exact value the CLI builds from argv and a cloud control plane serializes into a job.
 */
public sealed interface Command
        permits Command.Inventory, Command.BackupConfig, Command.RunRaw, Command.Discover,
                Command.ApplyConfig, Command.ConfigureSsid, Command.RestoreConfig {

    UUID commandId();

    /** Collect an inventory snapshot (show version / hw-info / interface / station). */
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

    /** Run a list of raw CLI commands and return their verbatim output. */
    record RunRaw(UUID commandId, DeviceRef device, List<String> commands) implements Command {

        public RunRaw {
            commands = List.copyOf(commands);
        }

        public static RunRaw of(DeviceRef device, List<String> commands) {
            return new RunRaw(UUID.randomUUID(), device, commands);
        }
    }

    /** Sweep a subnet for reachable hosts (runs on whichever node holds the engine — locally, or on the
     *  on-prem agent in the cloud topology, which is exactly where a LAN scan belongs). */
    record Discover(UUID commandId, String cidr, int port, int timeoutMillis) implements Command {
        public static Discover of(String cidr, int port, int timeoutMillis) {
            return new Discover(UUID.randomUUID(), cidr, port, timeoutMillis);
        }
    }

    /** Apply a list of raw CLI configuration lines, optionally persisting with {@code save config}. */
    record ApplyConfig(UUID commandId, DeviceRef device, List<String> commands, boolean save) implements Command {
        public ApplyConfig {
            commands = List.copyOf(commands);
        }

        public static ApplyConfig of(DeviceRef device, List<String> commands, boolean save) {
            return new ApplyConfig(UUID.randomUUID(), device, commands, save);
        }
    }

    /** Configure (or remove) a WPA2-PSK SSID, optionally on a VLAN. The driver generates the CLI. */
    record ConfigureSsid(UUID commandId, DeviceRef device, SsidSpec spec) implements Command {
        public static ConfigureSsid of(DeviceRef device, SsidSpec spec) {
            return new ConfigureSsid(UUID.randomUUID(), device, spec);
        }
    }

    /** Re-apply a captured running-config by replaying its lines (additive). */
    record RestoreConfig(UUID commandId, DeviceRef device, String runningConfig, boolean save) implements Command {
        public static RestoreConfig of(DeviceRef device, String runningConfig, boolean save) {
            return new RestoreConfig(UUID.randomUUID(), device, runningConfig, save);
        }
    }
}
