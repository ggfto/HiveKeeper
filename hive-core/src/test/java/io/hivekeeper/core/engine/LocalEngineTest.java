package io.hivekeeper.core.engine;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.drivers.HiveOsDriver;
import io.hivekeeper.core.model.BackupRef;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.testsupport.Fixtures;
import io.hivekeeper.core.transport.SshSession;
import io.hivekeeper.core.transport.SshTransport;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the full engine wiring (transport -> session -> driver -> task) with a fake transport,
 *  so there is no network dependency. */
class LocalEngineTest {

    /** Returns AP230 fixtures (or canned config) based on the command verb. */
    private static final class FakeSession implements SshSession {
        @Override
        public String exec(String command) {
            if (command.contains("running-config") && command.contains("users")) {
                return "ppsk-user demo password ***\n";
            }
            if (command.contains("running-config")) {
                return "hostname ap230-lab-1\nssid LabWifi\n";
            }
            if (command.contains("hw-info")) {
                return Fixtures.load("/fixtures/ap230/show_hw_info.txt");
            }
            if (command.contains("station")) {
                return Fixtures.load("/fixtures/ap230/show_station.txt");
            }
            if (command.contains("interface")) {
                return Fixtures.load("/fixtures/ap230/show_interface_mgt0.txt");
            }
            if (command.contains("version")) {
                return Fixtures.load("/fixtures/ap230/show_version.txt");
            }
            return "";
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingStore implements BackupStore {
        ConfigSnapshot captured;

        @Override
        public BackupRef write(ConfigSnapshot snapshot) {
            this.captured = snapshot;
            return new BackupRef("memory", "deadbeefcafe", "ap230-lab-1/running-config.txt");
        }
    }

    private final SshTransport transport = (device, creds) -> new FakeSession();
    private final CredentialProvider creds = id -> Optional.of(new Credentials("admin", "secret"));
    private final DriverRegistry drivers = new DriverRegistry(List.of(new HiveOsDriver()));

    @Test
    void inventoryParsesDeviceAndStreamsEvents() {
        CapturingStore store = new CapturingStore();
        Engine engine = new LocalEngine(transport, creds, drivers, store);

        List<Event> events = new ArrayList<>();
        DeviceRef ref = DeviceRef.ssh("192.168.1.10");
        Result result = engine.execute(Command.Inventory.of(ref), events::add);

        Result.Inventory inv = assertInstanceOf(Result.Inventory.class, result);
        assertEquals("AP230", inv.device().model());
        assertEquals("02301512211756", inv.device().serial());
        assertEquals("192.168.1.101", inv.device().managementIp());
        assertEquals(1, inv.device().stations().size());

        assertInstanceOf(Event.Started.class, events.get(0));
        assertInstanceOf(Event.Completed.class, events.get(events.size() - 1));
    }

    @Test
    void backupCapturesConfigAndUsersThenPersists() {
        CapturingStore store = new CapturingStore();
        Engine engine = new LocalEngine(transport, creds, drivers, store);

        DeviceRef ref = DeviceRef.ssh("192.168.1.10");
        Result result = engine.execute(Command.BackupConfig.of(ref), ev -> { });

        Result.Backup backup = assertInstanceOf(Result.Backup.class, result);
        assertTrue(backup.usersIncluded());
        assertTrue(backup.configBytes() > 0);
        assertEquals("deadbeefcafe", backup.ref().commitId());

        assertNotNull(store.captured);
        assertTrue(store.captured.runningConfig().contains("LabWifi"));
        assertEquals("10.6r1a", store.captured.firmwareVersion());
        assertNotNull(store.captured.usersConfig());
    }
}
