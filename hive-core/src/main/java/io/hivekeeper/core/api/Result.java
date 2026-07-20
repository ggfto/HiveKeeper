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
        permits Result.Inventory, Result.Backup, Result.RawCapture, Result.Discovered, Result.ConfigApplied,
        Result.ChannelsScanned, Result.BackupDestinationSet,
                Result.FirmwareUpgraded, Result.CredentialSet, Result.PpskUserManaged {

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
    /** The agent stored a new backup destination. Metadata only — the token never travels back. */
    record BackupDestinationSet(UUID commandId, DeviceId deviceId, String repoUrl, String branch,
                                boolean cleared) implements Result {
    }

    /** One {@link io.hivekeeper.core.model.ChannelScan} per radio, in the order the device reported them. */
    record ChannelsScanned(UUID commandId, DeviceId deviceId, List<io.hivekeeper.core.model.ChannelScan> scans)
            implements Result {
        public ChannelsScanned {
            scans = List.copyOf(scans);
        }
    }

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

    /** Outcome of a firmware upgrade: the image requested, the device's verbatim output, and whether a
     *  reboot was issued to activate it. {@code rebooting} true means the AP dropped the session to
     *  restart — verify the running version once it is back online. */
    record FirmwareUpgraded(UUID commandId, DeviceId deviceId, String imageUrl, String output, boolean rebooting)
            implements Result {
    }

    /** Outcome of a {@link Command.SetCredential}: which {@code credRef} was set, whether the agent vault
     *  was updated, and whether the admin password was also changed on the AP. Carries NO secret. */
    record CredentialSet(UUID commandId, DeviceId deviceId, String credRef, boolean vaultUpdated,
                         boolean deviceUpdated) implements Result {
    }

    /** Outcome of a {@link Command.ManagePpskUser}: the user and security object touched and the resulting
     *  status ({@code active} after create/rotate, {@code revoked} after revoke). Carries NO key — the PSK
     *  lives only in the on-prem store. The {@code deviceId} is a synthetic {@code ppsk:<so>} scope. */
    record PpskUserManaged(UUID commandId, DeviceId deviceId, String username, String securityObject,
                           String status) implements Result {
    }
}
