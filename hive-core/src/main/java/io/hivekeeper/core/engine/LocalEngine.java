package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.discovery.Scanner;
import io.hivekeeper.core.discovery.Subnets;
import io.hivekeeper.core.drivers.CliExecutor;
import io.hivekeeper.core.drivers.ConfigScope;
import io.hivekeeper.core.drivers.Driver;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.drivers.ProgressReporter;
import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.DiscoveryResult;
import io.hivekeeper.core.model.PpskUserRecord;
import io.hivekeeper.core.session.CommandRunner;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.PpskUserStore;
import io.hivekeeper.core.spi.SecretUnsealer;
import io.hivekeeper.core.spi.WritableCredentialProvider;
import io.hivekeeper.core.transport.SshSession;
import io.hivekeeper.core.transport.SshTransport;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The only {@link Engine} implementation in v0.1: runs everything in-process. Device-scoped commands
 * open an SSH session (driver picked by probing); the network-scoped {@link Command.Discover} runs a
 * subnet scan with no session. Tenant-unaware and stateless. A future RemoteEngine implements the same
 * interface over an agent channel — callers never learn which one they hold.
 */
@Slf4j
public final class LocalEngine implements Engine {

    private final SshTransport transport;
    private final CredentialProvider credentials;
    private final DriverRegistry drivers;
    private final BackupStore backupStore;
    private final Scanner scanner;
    private final WritableCredentialProvider writableCredentials;   // nullable — credential mgmt off when null
    private final SecretUnsealer unsealer;                          // nullable — credential/PPSK mgmt off when null
    private final PpskUserStore ppskUsers;                          // nullable — PPSK mgmt off when null

    public LocalEngine(SshTransport transport, CredentialProvider credentials, DriverRegistry drivers,
                       BackupStore backupStore, Scanner scanner) {
        this(transport, credentials, drivers, backupStore, scanner, null, null, null);
    }

    public LocalEngine(SshTransport transport, CredentialProvider credentials, DriverRegistry drivers,
                       BackupStore backupStore, Scanner scanner,
                       WritableCredentialProvider writableCredentials, SecretUnsealer unsealer) {
        this(transport, credentials, drivers, backupStore, scanner, writableCredentials, unsealer, null);
    }

    /**
     * Full constructor. Pass a {@code writableCredentials} + {@code unsealer} to enable
     * {@link Command.SetCredential} (the on-prem agent does); pass {@code null} for both to leave credential
     * management disabled. Pass a {@code ppskUsers} store (+ {@code unsealer}) to enable
     * {@link Command.ManagePpskUser}; {@code null} leaves PPSK key management disabled.
     */
    public LocalEngine(SshTransport transport, CredentialProvider credentials, DriverRegistry drivers,
                       BackupStore backupStore, Scanner scanner,
                       WritableCredentialProvider writableCredentials, SecretUnsealer unsealer,
                       PpskUserStore ppskUsers) {
        this.transport = transport;
        this.credentials = credentials;
        this.drivers = drivers;
        this.backupStore = backupStore;
        this.scanner = scanner;
        this.writableCredentials = writableCredentials;
        this.unsealer = unsealer;
        this.ppskUsers = ppskUsers;
    }

    @Override
    public Result execute(Command command, EventSink sink) throws HiveException {
        UUID id = command.commandId();

        if (command instanceof Command.Discover discover) {
            return discover(id, discover, sink);
        }
        if (command instanceof Command.SetCredential setCredential) {
            return setCredential(id, setCredential, sink);
        }
        if (command instanceof Command.ManagePpskUser managePpskUser) {
            return managePpskUser(id, managePpskUser, sink);
        }

        DeviceRef ref = deviceRefOf(command);
        DeviceId deviceId = ref.id();
        sink.emit(new Event.Started(id, deviceId, command.getClass().getSimpleName()));
        try {
            Credentials creds = credentials.resolve(ref)
                    .orElseThrow(() -> new IllegalStateException("No credentials configured for " + deviceId));

            try (SshSession session = transport.open(ref, creds)) {
                CliExecutor exec = new CommandRunner(session)::run;
                ProgressReporter progress = (percent, message) ->
                        sink.emit(new Event.Progress(id, deviceId, message, percent));

                Driver driver = drivers.identify(exec)
                        .orElseThrow(() -> new IOException("No driver recognizes this device"));
                log.info("device {} matched driver '{}'", deviceId, driver.id());
                sink.emit(new Event.Log(id, deviceId, "Matched driver: " + driver.id()));

                Result result = dispatch(command, id, deviceId, exec, progress, driver);
                sink.emit(new Event.Completed(id, deviceId, result));
                return result;
            }
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            log.warn("execute failed for {}: {}", deviceId, detail);
            sink.emit(new Event.Failed(id, deviceId, e.getClass().getSimpleName(), detail));
            throw new HiveException(id, deviceId, "execute failed: " + detail, e);
        }
    }

    private Result discover(UUID id, Command.Discover command, EventSink sink) throws HiveException {
        DeviceId scope = DeviceId.of(command.cidr());
        sink.emit(new Event.Started(id, scope, "Discover"));
        try {
            sink.emit(new Event.Progress(id, scope, "Scanning " + command.cidr(), 20));
            List<DiscoveryResult> hosts = scanner
                    .scan(Subnets.hostsForCidr(command.cidr()), command.port(), command.timeoutMillis())
                    .stream()
                    .filter(DiscoveryResult::reachable)
                    .toList();
            Result result = new Result.Discovered(id, scope, hosts);
            sink.emit(new Event.Progress(id, scope, hosts.size() + " host(s) reachable", 100));
            sink.emit(new Event.Completed(id, scope, result));
            return result;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            log.warn("discover failed for {}: {}", command.cidr(), detail);
            sink.emit(new Event.Failed(id, scope, e.getClass().getSimpleName(), detail));
            throw new HiveException(id, scope, "discover failed: " + detail, e);
        }
    }

    /**
     * Sets the SSH credential for a device. This is an agent-control operation, not a device CLI command:
     * the secret arrives sealed to this node's key, is unsealed locally, and written to the local vault —
     * the cloud never holds the plaintext. When {@code alsoSetOnDevice} is set, the admin password is first
     * changed ON the AP (authenticated with the CURRENT credential), so the vault is only updated after the
     * device has accepted the new password.
     */
    private Result setCredential(UUID id, Command.SetCredential cmd, EventSink sink) throws HiveException {
        DeviceRef ref = cmd.device();
        DeviceId deviceId = ref != null ? ref.id() : DeviceId.of(cmd.credRef());
        sink.emit(new Event.Started(id, deviceId, "SetCredential"));
        try {
            if (unsealer == null || writableCredentials == null) {
                throw new IllegalStateException("credential management is not enabled on this engine");
            }
            Credentials creds = unsealer.unseal(cmd.sealedSecret());

            boolean deviceUpdated = false;
            if (cmd.alsoSetOnDevice()) {
                if (ref == null) {
                    throw new IllegalStateException("a device is required to change the password on the AP");
                }
                // AP first, authenticated with the CURRENT credential, before the vault is touched.
                sink.emit(new Event.Progress(id, deviceId, "Changing the admin password on the device", 30));
                Credentials current = credentials.resolve(ref).orElseThrow(() ->
                        new IllegalStateException("No current credential to authenticate the change for " + deviceId));
                try (SshSession session = transport.open(ref, current)) {
                    CliExecutor exec = new CommandRunner(session)::run;
                    ProgressReporter progress = (percent, message) ->
                            sink.emit(new Event.Progress(id, deviceId, message, percent));
                    Driver driver = drivers.identify(exec)
                            .orElseThrow(() -> new IOException("No driver recognizes this device"));
                    List<String> lines = driver.adminPasswordCommands(creds.username(), creds.password());
                    driver.applyConfig(deviceId, exec, lines, true, progress);
                }
                deviceUpdated = true;
            }

            writableCredentials.store(cmd.credRef(), creds);
            sink.emit(new Event.Progress(id, deviceId, "Stored the credential in the agent vault", 100));
            Result result = new Result.CredentialSet(id, deviceId, cmd.credRef(), true, deviceUpdated);
            sink.emit(new Event.Completed(id, deviceId, result));
            return result;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            log.warn("set-credential failed for {}: {}", deviceId, detail);
            sink.emit(new Event.Failed(id, deviceId, e.getClass().getSimpleName(), detail));
            throw new HiveException(id, deviceId, "set-credential failed: " + detail, e);
        }
    }

    /**
     * Manages a Private-PSK user in the on-prem RADIUS store. Like {@link #setCredential} this is an
     * agent-control operation, not a device CLI command: the PSK arrives sealed to this node's key, is
     * unsealed locally, and written to the local store — the cloud never holds the usable key, and the AP
     * is never touched (HiveOS cannot mint a key over SSH). A {@code revoke} removes the user; a
     * {@code create}/{@code rotate} upserts it (with its returned VLAN / user-profile policy).
     */
    private Result managePpskUser(UUID id, Command.ManagePpskUser cmd, EventSink sink) throws HiveException {
        DeviceId scope = DeviceId.of("ppsk:" + cmd.securityObject());
        sink.emit(new Event.Started(id, scope, "ManagePpskUser"));
        try {
            if (ppskUsers == null) {
                throw new IllegalStateException("PPSK user management is not enabled on this engine");
            }
            String action = cmd.action() == null ? "" : cmd.action().toLowerCase(Locale.ROOT);
            String status;
            switch (action) {
                case "create", "rotate" -> {
                    if (unsealer == null) {
                        throw new IllegalStateException("no unsealer: cannot recover the sealed PSK on this engine");
                    }
                    Credentials creds = unsealer.unseal(cmd.sealedPsk());
                    ppskUsers.put(new PpskUserRecord(cmd.securityObject(), cmd.userGroup(), cmd.username(),
                            creds.password(), cmd.userProfileAttr(), cmd.vlanId(), cmd.scheduleName(),
                            cmd.macBindings(), "active"));
                    status = "active";
                    sink.emit(new Event.Progress(id, scope, "Provisioned PPSK user in the on-prem RADIUS store", 100));
                }
                case "revoke" -> {
                    ppskUsers.remove(cmd.securityObject(), cmd.username());
                    status = "revoked";
                    sink.emit(new Event.Progress(id, scope, "Revoked PPSK user from the on-prem RADIUS store", 100));
                }
                default -> throw new IllegalArgumentException("unknown PPSK action: " + cmd.action());
            }
            Result result = new Result.PpskUserManaged(id, scope, cmd.username(), cmd.securityObject(), status);
            sink.emit(new Event.Completed(id, scope, result));
            return result;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            log.warn("manage-ppsk-user failed for {}: {}", scope, detail);
            sink.emit(new Event.Failed(id, scope, e.getClass().getSimpleName(), detail));
            throw new HiveException(id, scope, "manage-ppsk-user failed: " + detail, e);
        }
    }

    private Result dispatch(Command command, UUID id, DeviceId deviceId, CliExecutor exec,
                            ProgressReporter progress, Driver driver) throws IOException {
        return switch (command) {
            case Command.Inventory ignored -> {
                Device device = driver.inventory(deviceId, exec, progress);
                yield new Result.Inventory(id, deviceId, device);
            }
            case Command.BackupConfig c -> {
                ConfigSnapshot snapshot = driver.captureConfig(
                        deviceId, exec, new ConfigScope(c.includeUsers(), c.includeSecrets()), progress);
                BackupRef ref = backupStore.write(snapshot);
                int bytes = snapshot.runningConfig() == null
                        ? 0 : snapshot.runningConfig().getBytes(StandardCharsets.UTF_8).length;
                yield new Result.Backup(id, deviceId, ref, snapshot.usersConfig() != null, bytes);
            }
            case Command.RunRaw c -> {
                Map<String, String> outputs = new LinkedHashMap<>();
                int total = Math.max(1, c.commands().size());
                int i = 0;
                for (String raw : c.commands()) {
                    progress.report((int) Math.round(++i * 100.0 / total), "Running: " + raw);
                    outputs.put(raw, exec.run(raw));
                }
                yield new Result.RawCapture(id, deviceId, outputs);
            }
            case Command.ScanChannels ignored -> {
                progress.report(10, "Scanning the air");
                yield new Result.ChannelsScanned(id, deviceId, driver.channelScans(exec, progress));
            }
            case Command.ApplyConfig c -> {
                List<String> outputs = driver.applyConfig(deviceId, exec, c.commands(), c.save(), progress);
                yield new Result.ConfigApplied(id, deviceId, c.commands(), outputs, c.save());
            }
            case Command.ConfigureSsid c -> {
                // Ask the device what radios it has before deciding what to bind the SSID to — an AP410C-1
                // has three, and a hardcoded pair would leave the third silently off the air.
                List<String> lines = driver.ssidCommands(c.spec(), driver.radioInterfaces(exec));
                List<String> outputs = driver.applyConfig(deviceId, exec, lines, true, progress);
                yield new Result.ConfigApplied(id, deviceId, lines, outputs, true);
            }
            case Command.ConfigureHive c -> {
                List<String> lines = driver.hiveCommands(c.spec());
                List<String> outputs = driver.applyConfig(deviceId, exec, lines, true, progress);
                yield new Result.ConfigApplied(id, deviceId, lines, outputs, true);
            }
            case Command.Reboot ignored -> {
                progress.report(50, "Rebooting device");
                String output = driver.reboot(deviceId, exec);
                progress.report(100, "Reboot initiated");
                yield new Result.ConfigApplied(id, deviceId, List.of("reboot"), List.of(output), false);
            }
            case Command.RestoreConfig c -> {
                List<String> lines = c.runningConfig().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
                List<String> outputs = driver.applyConfig(deviceId, exec, lines, c.save(), progress);
                yield new Result.ConfigApplied(id, deviceId, lines, outputs, c.save());
            }
            case Command.FirmwareUpgrade c -> {
                String output = driver.upgradeFirmware(deviceId, exec, c.imageUrl(), c.reboot(), progress);
                yield new Result.FirmwareUpgraded(id, deviceId, c.imageUrl(), output, c.reboot());
            }
            case Command.Discover ignored -> throw new IllegalStateException("discover is handled without a session");
            case Command.SetCredential ignored ->
                    throw new IllegalStateException("set-credential is handled before SSH dispatch");
            case Command.ManagePpskUser ignored ->
                    throw new IllegalStateException("manage-ppsk-user is handled before SSH dispatch");
            case Command.Sealed ignored ->
                    throw new IllegalStateException("sealed commands are unwrapped by the agent before the engine");
        };
    }

    private static DeviceRef deviceRefOf(Command command) {
        return switch (command) {
            case Command.Inventory c -> c.device();
            case Command.ScanChannels c -> c.device();
            case Command.BackupConfig c -> c.device();
            case Command.RunRaw c -> c.device();
            case Command.ApplyConfig c -> c.device();
            case Command.ConfigureSsid c -> c.device();
            case Command.ConfigureHive c -> c.device();
            case Command.Reboot c -> c.device();
            case Command.RestoreConfig c -> c.device();
            case Command.FirmwareUpgrade c -> c.device();
            case Command.Discover ignored -> throw new IllegalStateException("discover is network-scoped");
            case Command.SetCredential ignored ->
                    throw new IllegalStateException("set-credential is handled before deviceRefOf");
            case Command.ManagePpskUser ignored ->
                    throw new IllegalStateException("manage-ppsk-user is handled before deviceRefOf");
            case Command.Sealed ignored ->
                    throw new IllegalStateException("sealed commands are unwrapped by the agent before deviceRefOf");
        };
    }
}
