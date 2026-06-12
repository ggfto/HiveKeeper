package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.core.testsupport.FakeAp230Cli;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveOsDriverTest {

    private final HiveOsDriver driver = new HiveOsDriver();
    private final CliExecutor ap230 = new FakeAp230Cli();

    @Test
    void recognizesHiveOsButNotOtherVendors() throws Exception {
        assertTrue(driver.recognizes(ap230));
        assertFalse(driver.recognizes(cmd -> "RouterOS 7.1 (stable)"));
        assertFalse(HiveOsDriver.isHiveOs(null));
    }

    @Test
    void inventoryParsesRealAp230Fields() throws Exception {
        Device d = driver.inventory(DeviceId.of("192.168.1.101"), ap230, ProgressReporter.NOOP);

        assertEquals("AP230", d.model());
        assertEquals("02301512211756", d.serial());
        assertEquals("10.6r1a", d.firmwareVersion());
        assertEquals("192.168.1.101", d.managementIp());
        assertTrue(d.uptime().contains("hours"), "uptime was: " + d.uptime());
    }

    @Test
    void inventoryParsesHiveNameAndRadios() throws Exception {
        Device d = driver.inventory(DeviceId.of("192.168.1.101"), ap230, ProgressReporter.NOOP);

        assertEquals("hive0", d.hiveName());
        assertEquals(2, d.radios().size());

        var wifi0 = d.radios().stream().filter(r -> r.name().equals("Wifi0")).findFirst().orElseThrow();
        assertEquals("access", wifi0.mode());
        assertEquals(1, wifi0.channel());

        var wifi1 = d.radios().stream().filter(r -> r.name().equals("Wifi1")).findFirst().orElseThrow();
        assertEquals("dual", wifi1.mode());
        assertEquals(165, wifi1.channel());
    }

    @Test
    void inventoryParsesStationWithAerohiveMacAndSsid() throws Exception {
        Device d = driver.inventory(DeviceId.of("192.168.1.101"), ap230, ProgressReporter.NOOP);

        assertEquals(1, d.stations().size());
        var s = d.stations().get(0);
        assertEquals("d0c6:37e5:9250", s.mac());
        assertEquals("192.168.1.102", s.ipAddress());
        assertEquals("TESTE", s.ssid());
        assertEquals(-73, s.rssi());
    }

    @Test
    void captureConfigIncludesRunningAndSeparateUsersChannel() throws Exception {
        ConfigSnapshot snap =
                driver.captureConfig(DeviceId.of("192.168.1.101"), ap230, ConfigScope.full(), ProgressReporter.NOOP);

        assertTrue(snap.runningConfig().contains("TESTE"));
        assertNotNull(snap.usersConfig());
        assertEquals("10.6r1a", snap.firmwareVersion());
        assertNotNull(snap.capturedAt());
    }

    @Test
    void captureConfigWithoutUsersOmitsThatChannel() throws Exception {
        ConfigSnapshot snap = driver.captureConfig(
                DeviceId.of("x"), ap230, new ConfigScope(false, false), ProgressReporter.NOOP);

        assertNull(snap.usersConfig());
    }

    @Test
    void buildsSsidCreateCommandsWithVlan() {
        List<String> commands = driver.ssidCommands(SsidSpec.create("HK", "secretpass", 30));

        assertTrue(commands.contains("security-object HK"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("ascii-key secretpass")));
        assertTrue(commands.contains("ssid HK security-object HK"));
        assertTrue(commands.contains("interface wifi0 ssid HK"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("vlan-id 30")));
        assertTrue(commands.contains("security-object HK default-user-profile-attr 30"));
    }

    @Test
    void buildsSsidRemoveCommands() {
        List<String> commands = driver.ssidCommands(SsidSpec.removal("HK"));

        assertTrue(commands.contains("no ssid HK"));
        assertTrue(commands.contains("no security-object HK"));
    }

    @Test
    void applyConfigSendsEachLineThenSaves() throws Exception {
        List<String> sent = new ArrayList<>();
        CliExecutor recorder = command -> {
            sent.add(command);
            return "";
        };

        List<String> outputs = driver.applyConfig(DeviceId.of("ap"), recorder,
                List.of("ssid X", "ssid X security-object X"), true, ProgressReporter.NOOP);

        assertEquals(3, outputs.size());                 // 2 lines + save config
        assertTrue(sent.contains("save config"));
    }
}
