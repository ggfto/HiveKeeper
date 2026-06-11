package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.Station;
import io.hivekeeper.core.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveOsDriverTest {

    private final HiveOsDriver driver = new HiveOsDriver();

    private static HiveOsCapture ap230Capture() {
        return new HiveOsCapture(
                Fixtures.load("/fixtures/ap230/show_version.txt"),
                Fixtures.load("/fixtures/ap230/show_hw_info.txt"),
                Fixtures.load("/fixtures/ap230/show_interface_mgt0.txt"),
                Fixtures.load("/fixtures/ap230/show_station.txt"));
    }

    @Test
    void recognizesHiveOsFromVersionOutput() {
        assertTrue(driver.supports(Fixtures.load("/fixtures/ap230/show_version.txt")));
        assertFalse(driver.supports("RouterOS 7.1 (stable)"));
        assertFalse(driver.supports(null));
    }

    @Test
    void parsesInventoryFromRealAp230Captures() {
        Device d = driver.parseDevice(DeviceId.of("192.168.1.101"), ap230Capture());

        assertEquals("AP230", d.model());
        assertEquals("02301512211756", d.serial());
        assertEquals("10.6r1a", d.firmwareVersion());
        assertEquals("192.168.1.101", d.managementIp());
        assertTrue(d.uptime().contains("hours"), "uptime was: " + d.uptime());
    }

    @Test
    void parsesStationsWithAerohiveMacAndSsidGrouping() {
        Device d = driver.parseDevice(DeviceId.of("192.168.1.101"), ap230Capture());

        assertEquals(1, d.stations().size());
        Station s = d.stations().get(0);
        assertEquals("d0c6:37e5:9250", s.mac());
        assertEquals("192.168.1.102", s.ipAddress());
        assertEquals("TESTE", s.ssid());
        assertEquals(-73, s.rssi());
    }
}
