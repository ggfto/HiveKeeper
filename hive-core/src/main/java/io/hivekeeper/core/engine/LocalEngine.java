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
import io.hivekeeper.core.session.CommandRunner;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.transport.SshSession;
import io.hivekeeper.core.transport.SshTransport;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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

    public LocalEngine(SshTransport transport, CredentialProvider credentials, DriverRegistry drivers,
                       BackupStore backupStore, Scanner scanner) {
        this.transport = transport;
        this.credentials = credentials;
        this.drivers = drivers;
        this.backupStore = backupStore;
        this.scanner = scanner;
    }

    @Override
    public Result execute(Command command, EventSink sink) throws HiveException {
        UUID id = command.commandId();

        if (command instanceof Command.Discover discover) {
            return discover(id, discover, sink);
        }

        DeviceRef ref = deviceRefOf(command);
        DeviceId deviceId = ref.id();
        sink.emit(new Event.Started(id, deviceId, command.getClass().getSimpleName()));
        try {
            Credentials creds = credentials.resolve(deviceId)
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
            case Command.ApplyConfig c -> {
                List<String> outputs = driver.applyConfig(deviceId, exec, c.commands(), c.save(), progress);
                yield new Result.ConfigApplied(id, deviceId, c.commands(), outputs, c.save());
            }
            case Command.ConfigureSsid c -> {
                List<String> lines = driver.ssidCommands(c.spec());
                List<String> outputs = driver.applyConfig(deviceId, exec, lines, true, progress);
                yield new Result.ConfigApplied(id, deviceId, lines, outputs, true);
            }
            case Command.RestoreConfig c -> {
                List<String> lines = c.runningConfig().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
                List<String> outputs = driver.applyConfig(deviceId, exec, lines, c.save(), progress);
                yield new Result.ConfigApplied(id, deviceId, lines, outputs, c.save());
            }
            case Command.Discover ignored -> throw new IllegalStateException("discover is handled without a session");
        };
    }

    private static DeviceRef deviceRefOf(Command command) {
        return switch (command) {
            case Command.Inventory c -> c.device();
            case Command.BackupConfig c -> c.device();
            case Command.RunRaw c -> c.device();
            case Command.ApplyConfig c -> c.device();
            case Command.ConfigureSsid c -> c.device();
            case Command.RestoreConfig c -> c.device();
            case Command.Discover ignored -> throw new IllegalStateException("discover is network-scoped");
        };
    }
}
