package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import io.hivekeeper.core.testsupport.FakeAp230Cli;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveOsDriverTest {

    private final HiveOsDriver driver = new HiveOsDriver();
    private final CliExecutor ap230 = new FakeAp230Cli();

    /** The classic two-radio layout (AP230, AP630). Named so the radio count of each case is explicit. */
    private static final List<String> TWO_RADIOS = List.of("wifi0", "wifi1");

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
        List<String> commands = driver.ssidCommands(SsidSpec.create("HK", "secretpass", 30), TWO_RADIOS);

        assertTrue(commands.contains("security-object HK"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("ascii-key secretpass")));
        assertTrue(commands.contains("ssid HK security-object HK"));
        assertTrue(commands.contains("interface wifi0 ssid HK"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("vlan-id 30")));
        assertTrue(commands.contains("security-object HK default-user-profile-attr 30"));
    }

    @Test
    void buildsWpa3SaeSsidWithAsciiKey() {
        List<String> commands = driver.ssidCommands(SsidSpec.create("HK3", "secretpass", null, SsidSpec.WPA3_SAE), TWO_RADIOS);

        assertTrue(commands.contains("security-object HK3 security protocol-suite wpa3-sae ascii-key secretpass"));
        assertTrue(commands.contains("ssid HK3 security-object HK3"));
    }

    @Test
    void buildsOpenSsidWithoutAKey() {
        List<String> commands = driver.ssidCommands(SsidSpec.create("HKguest", null, null, SsidSpec.OPEN), TWO_RADIOS);

        assertTrue(commands.contains("security-object HKguest security protocol-suite open"));
        assertFalse(commands.stream().anyMatch(c -> c.contains("ascii-key")));
    }

    @Test
    void buildsEnterpriseSsidBindingARadiusServer() {
        SsidSpec.RadiusSpec radius = new SsidSpec.RadiusSpec("10.0.0.5", "r4dsecret", 1812);
        List<String> commands = driver.ssidCommands(SsidSpec.createEnterprise("Corp", null, SsidSpec.WPA2_8021X, radius), TWO_RADIOS);

        assertTrue(commands.contains("security-object Corp security protocol-suite wpa2-aes-8021x"));
        assertTrue(commands.contains(
                "security-object Corp security aaa radius-server primary 10.0.0.5 shared-secret r4dsecret auth-port 1812"));
        assertFalse(commands.stream().anyMatch(c -> c.contains("ascii-key")));
    }

    @Test
    void buildsSsidRemoveCommands() {
        List<String> commands = driver.ssidCommands(SsidSpec.removal("HK"), TWO_RADIOS);

        assertTrue(commands.contains("no ssid HK"));
        assertTrue(commands.contains("no security-object HK"));
    }

    @Test
    void discoversRadioInterfacesFromTheDeviceRatherThanAssumingTwo() throws Exception {
        // The AP410C-1 reports three physical radios (its second and third are both 5 GHz), interleaved with
        // the WifiN.x virtual interfaces, which are not radios and must not be bound.
        CliExecutor ap410c = command -> command.contains("interface")
                ? io.hivekeeper.core.testsupport.Fixtures.load("/fixtures/ap410c/show_interface.txt")
                : "";

        assertEquals(List.of("wifi0", "wifi1", "wifi2"), driver.radioInterfaces(ap410c));
        assertEquals(List.of("wifi0", "wifi1"), driver.radioInterfaces(ap230));
    }

    @Test
    void bindsAnSsidToEveryRadioTheDeviceReports() {
        List<String> commands =
                driver.ssidCommands(SsidSpec.create("HK", "secretpass", null), List.of("wifi0", "wifi1", "wifi2"));

        assertTrue(commands.contains("interface wifi0 ssid HK"));
        assertTrue(commands.contains("interface wifi1 ssid HK"));
        assertTrue(commands.contains("interface wifi2 ssid HK"),
                "the third radio must carry the SSID too, or half the 5 GHz capacity is silently idle");
    }

    @Test
    void bindsAnSsidToAFourthRadioNoHardwareHereHasYet() {
        // No AP in the lab has four radios. The point of discovering them is that one would need no code
        // change, so assert that directly rather than waiting for the hardware to exist.
        List<String> commands = driver.ssidCommands(SsidSpec.create("HK", "secretpass", null),
                List.of("wifi0", "wifi1", "wifi2", "wifi3"));

        assertTrue(commands.contains("interface wifi3 ssid HK"));
    }

    @Test
    void unbindsFromEveryRadioOnRemoval() {
        List<String> commands = driver.ssidCommands(SsidSpec.removal("HK"), List.of("wifi0", "wifi1", "wifi2"));

        assertTrue(commands.contains("no interface wifi2 ssid HK"),
                "leaving a binding behind on the third radio orphans the SSID there");
    }

    @Test
    void refusesToCreateAnSsidWhenNoRadioWasDiscovered() {
        // Silently producing an SSID bound to nothing would be the old bug wearing a different hat.
        assertThrows(IllegalStateException.class,
                () -> driver.ssidCommands(SsidSpec.create("HK", "secretpass", null), List.of()));
    }

    @Test
    void buildsOweSsidWithoutAKey() {
        List<String> commands = driver.ssidCommands(SsidSpec.create("HKopen", null, null, SsidSpec.OWE), TWO_RADIOS);

        assertTrue(commands.contains("security-object HKopen security protocol-suite owe"));
        assertFalse(commands.stream().anyMatch(c -> c.contains("ascii-key")));
    }

    @Test
    void buildsWpa3Enterprise192SsidBindingARadiusServer() {
        SsidSpec.RadiusSpec radius = new SsidSpec.RadiusSpec("10.0.0.5", "r4dsecret", null);
        List<String> commands = driver.ssidCommands(
                SsidSpec.createEnterprise("Gov", null, SsidSpec.WPA3_8021X_192, radius), TWO_RADIOS);

        assertTrue(commands.contains("security-object Gov security protocol-suite wpa3-aes-8021x-suite-b-192"));
        assertTrue(commands.contains(
                "security-object Gov security aaa radius-server primary 10.0.0.5 shared-secret r4dsecret"));
    }

    @Test
    void buildsHiveCommandsBindingManagementInterface() {
        List<String> commands = driver.hiveCommands(HiveSpec.of("hk-hive", "meshsecret123"));

        assertTrue(commands.contains("hive hk-hive"));
        assertTrue(commands.contains("hive hk-hive password meshsecret123"));
        assertTrue(commands.contains("interface mgt0 hive hk-hive"));
    }

    @Test
    void buildsAdminPasswordCommandForARootAdmin() {
        // Grammar confirmed live on the AP230: admin root-admin <name> password <string>.
        assertEquals(List.of("admin root-admin admin password Aerohive1"),
                driver.adminPasswordCommands("admin", "Aerohive1"));
    }

    @Test
    void enforcesTheLiveConfirmedHiveOsPasswordPolicy() {
        // Policy observed live: 8-32 chars, >=1 digit, >=1 uppercase, != username/'password'.
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("admin", "Short1"));        // too short
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("admin", "A" + "a1".repeat(20))); // too long (>32)
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("admin", "aerohive"));     // no digit/uppercase
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("admin", "Aerohivex"));    // no digit
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("admin", "aerohive1"));    // no uppercase
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("Aerohive1", "Aerohive1")); // == username
        assertThrows(IllegalArgumentException.class, () -> driver.adminPasswordCommands("", "Aerohive1"));         // no username
    }

    @Test
    void rebootTreatsDroppedSessionAsSuccess() throws Exception {
        CliExecutor dropping = command -> {
            throw new java.io.IOException("connection reset");
        };

        String output = driver.reboot(DeviceId.of("ap"), dropping);

        assertTrue(output.toLowerCase().contains("reboot"));
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
