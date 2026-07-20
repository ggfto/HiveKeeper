package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.drivers.CliExecutor;
import io.hivekeeper.core.drivers.ConfigScope;
import io.hivekeeper.core.drivers.Driver;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.drivers.ProgressReporter;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.SecretUnsealer;
import io.hivekeeper.core.spi.WritableCredentialProvider;
import io.hivekeeper.core.transport.SshSession;
import io.hivekeeper.core.transport.SshTransport;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives {@link LocalEngine#execute} for {@link Command.SetCredential} with in-memory fakes — no network
 *  and no real crypto, so it pins the engine's behavior: unseal locally, AP-first, then write the vault. */
class LocalEngineSetCredentialTest {

    /** Records the CLI lines it was asked to run so the test can assert the on-device change happened. */
    private static final class RecordingSession implements SshSession {
        final List<String> ran = new ArrayList<>();

        @Override
        public String exec(String command) {
            ran.add(command);
            return command.contains("version") ? "HiveOS IQ Engine" : "";
        }

        @Override
        public void close() {
        }
    }

    /** A minimal driver that recognizes everything and knows how to change the admin password. */
    private static final class FakeDriver implements Driver {
        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean recognizes(CliExecutor exec) {
            return true;
        }

        @Override
        public Device inventory(DeviceId id, CliExecutor exec, ProgressReporter progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigSnapshot captureConfig(DeviceId id, CliExecutor exec, ConfigScope scope, ProgressReporter p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> applyConfig(DeviceId id, CliExecutor exec, List<String> commands, boolean save,
                                        ProgressReporter progress) throws java.io.IOException {
            List<String> out = new ArrayList<>();
            for (String c : commands) {
                out.add(exec.run(c));
            }
            if (save) {
                exec.run("save config");
            }
            return out;
        }

        @Override
        public List<String> ssidCommands(SsidSpec spec, List<String> radioInterfaces) {
            return List.of();
        }

        @Override
        public List<String> hiveCommands(HiveSpec spec) {
            return List.of();
        }

        @Override
        public List<String> adminPasswordCommands(String username, String newPassword) {
            return List.of("admin " + username + " password " + newPassword);
        }
    }

    /** An in-memory writable vault. */
    private static final class MemoryVault implements WritableCredentialProvider {
        final Map<String, Credentials> byRef = new HashMap<>();

        @Override
        public Optional<Credentials> resolve(DeviceRef device) {
            return Optional.ofNullable(byRef.get(device.credRef()));
        }

        @Override
        public void store(String credRef, Credentials credentials) {
            byRef.put(credRef, credentials);
        }
    }

    private final RecordingSession session = new RecordingSession();
    private final SshTransport transport = (device, creds) -> session;
    private final DriverRegistry drivers = new DriverRegistry(List.of(new FakeDriver()));
    private final BackupStore store = snapshot -> null;
    // The "current" credential the engine authenticates the on-device change with.
    private final CredentialProvider current = ref -> Optional.of(new Credentials("admin", "oldpass"));
    // The unsealer hands back the NEW credential (stands in for EnvelopeCipher + private key).
    private final SecretUnsealer unsealer = token -> new Credentials("admin", "newpass");

    @Test
    void vaultOnlyWriteStoresTheCredentialWithoutTouchingTheDevice() {
        MemoryVault vault = new MemoryVault();
        Engine engine = new LocalEngine(transport, current, drivers, store, null, vault, unsealer);

        Result result = engine.execute(
                Command.SetCredential.of(DeviceRef.ssh("192.168.1.10", 22, "dev-1"), "dev-1", "sealed", false),
                ev -> { });

        Result.CredentialSet set = assertInstanceOf(Result.CredentialSet.class, result);
        assertTrue(set.vaultUpdated());
        assertFalse(set.deviceUpdated());
        assertEquals("newpass", vault.byRef.get("dev-1").password());
        assertTrue(session.ran.isEmpty(), "vault-only write must not open an SSH session");
    }

    @Test
    void onDeviceChangeAppliesThePasswordLineBeforeStoringTheVault() {
        MemoryVault vault = new MemoryVault();
        Engine engine = new LocalEngine(transport, current, drivers, store, null, vault, unsealer);

        Result result = engine.execute(
                Command.SetCredential.of(DeviceRef.ssh("192.168.1.10", 22, "dev-1"), "dev-1", "sealed", true),
                ev -> { });

        Result.CredentialSet set = assertInstanceOf(Result.CredentialSet.class, result);
        assertTrue(set.deviceUpdated());
        assertTrue(set.vaultUpdated());
        assertTrue(session.ran.contains("admin admin password newpass"), session.ran.toString());
        assertTrue(session.ran.contains("save config"));
        assertEquals("newpass", vault.byRef.get("dev-1").password());
    }

    @Test
    void setCredentialIsRefusedWhenCredentialManagementIsNotEnabled() {
        Engine engine = new LocalEngine(transport, current, drivers, store, null);   // no vault, no unsealer

        assertThrows(HiveException.class, () -> engine.execute(
                Command.SetCredential.of(DeviceRef.ssh("192.168.1.10", 22, "dev-1"), "dev-1", "sealed", false),
                ev -> { }));
    }
}
