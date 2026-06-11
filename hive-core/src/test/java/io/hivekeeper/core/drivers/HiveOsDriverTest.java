package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveOsDriverTest {

    private final HiveOsDriver driver = new HiveOsDriver();

    @Test
    void recognizesHiveOsFromVersionOutput() {
        assertTrue(driver.supports(Fixtures.load("/fixtures/ap230/show_version.txt")));
        assertFalse(driver.supports("RouterOS 7.1 (stable)"));
        assertFalse(driver.supports(null));
    }

    @Test
    void parsesInventoryFromFixtures() {
        HiveOsCapture capture = new HiveOsCapture(
                Fixtures.load("/fixtures/ap230/show_version.txt"),
                Fixtures.load("/fixtures/ap230/show_interface.txt"),
                Fixtures.load("/fixtures/ap230/show_station.txt"));

        Device d = driver.parseDevice(DeviceId.of("ap230-lab-1"), capture);

        assertEquals("AP230", d.model());
        assertEquals("01234567890ABCDE", d.serial());
        assertEquals("10.0r7a", d.firmwareVersion());
        assertEquals("ap230-lab-1", d.hostname());
        assertTrue(d.uptime().contains("5 days"), "uptime was: " + d.uptime());
        assertEquals("192.168.1.10", d.managementIp());

        assertEquals(2, d.stations().size());
        assertEquals("1c:36:bb:00:11:22", d.stations().get(0).mac());
        assertEquals("192.168.1.50", d.stations().get(0).ipAddress());
    }
}
