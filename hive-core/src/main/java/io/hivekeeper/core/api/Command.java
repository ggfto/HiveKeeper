package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
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
                Command.ScanChannels,
                Command.ApplyConfig, Command.ConfigureSsid, Command.ConfigureHive, Command.Reboot,
                Command.RestoreConfig, Command.FirmwareUpgrade, Command.SetCredential,
                Command.ManagePpskUser, Command.Sealed {

    UUID commandId();

    /** Collect an inventory snapshot (show version / hw-info / interface / station). */
    record Inventory(UUID commandId, DeviceRef device) implements Command {
        public static Inventory of(DeviceRef device) {
            return new Inventory(UUID.randomUUID(), device);
        }
    }

    /**
     * Read the AP's own view of the air: the cost it assigns each candidate channel, and the neighbouring
     * BSSIDs it heard. Read-only — it reports what automatic channel selection already measured so a human
     * can decide, and changes nothing.
     */
    record ScanChannels(UUID commandId, DeviceRef device) implements Command {
        public static ScanChannels of(DeviceRef device) {
            return new ScanChannels(UUID.randomUUID(), device);
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

    /** Configure (join) this device's hive/mesh membership. The driver generates the CLI. */
    record ConfigureHive(UUID commandId, DeviceRef device, HiveSpec spec) implements Command {
        public static ConfigureHive of(DeviceRef device, HiveSpec spec) {
            return new ConfigureHive(UUID.randomUUID(), device, spec);
        }
    }

    /** Reboot the device. The session drops as the AP restarts; that disconnect is the success signal. */
    record Reboot(UUID commandId, DeviceRef device) implements Command {
        public static Reboot of(DeviceRef device) {
            return new Reboot(UUID.randomUUID(), device);
        }
    }

    /** Re-apply a captured running-config by replaying its lines (additive). */
    record RestoreConfig(UUID commandId, DeviceRef device, String runningConfig, boolean save) implements Command {
        public static RestoreConfig of(DeviceRef device, String runningConfig, boolean save) {
            return new RestoreConfig(UUID.randomUUID(), device, runningConfig, save);
        }
    }

    /**
     * Upgrade the device firmware from an image hosted at {@code imageUrl} (a TFTP/FTP/HTTP location the
     * AP can reach), optionally rebooting afterwards to activate it. The driver owns the actual CLI
     * vocabulary. NOTE: this path is unverified against a live AP in v0.1 — see {@code HiveOsDriver}.
     */
    record FirmwareUpgrade(UUID commandId, DeviceRef device, String imageUrl, boolean reboot) implements Command {
        public static FirmwareUpgrade of(DeviceRef device, String imageUrl, boolean reboot) {
            return new FirmwareUpgrade(UUID.randomUUID(), device, imageUrl, reboot);
        }
    }

    /**
     * Set (or rotate) the SSH credential HiveKeeper uses for a device, keyed by {@code credRef}. Unlike
     * every other command this is NOT a device CLI operation by default — it writes the on-prem agent's
     * local credential vault, so the secret is resolved locally and never persisted in the cloud. The
     * secret travels as {@code sealedSecret}, an {@link io.hivekeeper.core.crypto.EnvelopeCipher} token of
     * the JSON {@code {"username":...,"password":...}} sealed to the agent's public key — the engine that
     * handles this command unseals it locally. When {@code alsoSetOnDevice} is true the agent additionally
     * changes the admin password ON the AP itself (via the driver) BEFORE updating the vault, so a failed
     * device change never leaves the vault pointing at a password the AP never accepted.
     */
    record SetCredential(UUID commandId, DeviceRef device, String credRef, String sealedSecret,
                         boolean alsoSetOnDevice) implements Command {
        public static SetCredential of(DeviceRef device, String credRef, String sealedSecret,
                                       boolean alsoSetOnDevice) {
            return new SetCredential(UUID.randomUUID(), device, credRef, sealedSecret, alsoSetOnDevice);
        }
    }

    /**
     * Manage a Private-PSK user in the on-prem RADIUS store (PPSK "Caminho B"). Like {@link SetCredential}
     * this is NOT a device CLI operation — it provisions the on-prem agent's RADIUS store, never the AP
     * (HiveOS has no running-config grammar to mint a key over SSH). {@code action} is
     * {@code create}/{@code rotate}/{@code revoke}; for create/rotate the PSK travels as {@code sealedPsk},
     * an {@link io.hivekeeper.core.crypto.EnvelopeCipher} token of the JSON-free
     * {@link io.hivekeeper.core.crypto.CredentialPayload} encoding {@code username\npsk} sealed to the
     * agent's public key (the engine unseals it locally, so the cloud never holds the usable key).
     * {@code userProfileAttr}/{@code vlanId} are the policy the RADIUS server returns on Accept;
     * {@code macBindings} optional bound client MACs; {@code scheduleName} an optional validity window.
     * A {@code revoke} carries only {@code securityObject}+{@code username}.
     */
    record ManagePpskUser(UUID commandId, String action, String securityObject, String userGroup,
                          String username, String sealedPsk, Integer userProfileAttr, Integer vlanId,
                          String scheduleName, List<String> macBindings) implements Command {

        public ManagePpskUser {
            macBindings = macBindings == null ? List.of() : List.copyOf(macBindings);
        }

        public static ManagePpskUser create(String securityObject, String userGroup, String username,
                                            String sealedPsk, Integer userProfileAttr, Integer vlanId,
                                            String scheduleName, List<String> macBindings) {
            return new ManagePpskUser(UUID.randomUUID(), "create", securityObject, userGroup, username,
                    sealedPsk, userProfileAttr, vlanId, scheduleName, macBindings);
        }

        public static ManagePpskUser rotate(String securityObject, String userGroup, String username,
                                            String sealedPsk, Integer userProfileAttr, Integer vlanId,
                                            String scheduleName, List<String> macBindings) {
            return new ManagePpskUser(UUID.randomUUID(), "rotate", securityObject, userGroup, username,
                    sealedPsk, userProfileAttr, vlanId, scheduleName, macBindings);
        }

        public static ManagePpskUser revoke(String securityObject, String username) {
            return new ManagePpskUser(UUID.randomUUID(), "revoke", securityObject, null, username, null,
                    null, null, null, List.of());
        }
    }

    /**
     * An envelope around another command whose payload is sealed to the on-prem agent's public key. The cloud
     * persists/dispatches only this wrapper, so a secret-bearing command (an SSID passphrase, a hive password)
     * is unreadable by the gateway at rest — only the agent, holding the private key, can recover the inner
     * command. {@code sealedCommand} is an {@link io.hivekeeper.core.crypto.EnvelopeCipher} token (RSA-OAEP +
     * AES-GCM; {@code plain1:} dev fallback) of the inner command's wire JSON. The agent unwraps it (unseal →
     * deserialize) and executes the inner command; no other node ever sees the wrapper unwrapped.
     */
    record Sealed(UUID commandId, String sealedCommand) implements Command {
        public static Sealed of(String sealedCommand) {
            return new Sealed(UUID.randomUUID(), sealedCommand);
        }
    }
}
