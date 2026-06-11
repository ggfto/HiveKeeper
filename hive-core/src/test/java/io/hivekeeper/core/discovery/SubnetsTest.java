package io.hivekeeper.core.discovery;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubnetsTest {

    @Test
    void slash24HasAllUsableHosts() {
        List<String> hosts = Subnets.hostsForCidr("192.168.1.0/24");
        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
        assertEquals("192.168.1.254", hosts.get(hosts.size() - 1));
        assertFalse(hosts.contains("192.168.1.0"), "network address excluded");
        assertFalse(hosts.contains("192.168.1.255"), "broadcast address excluded");
    }

    @Test
    void slash30HasTwoHosts() {
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), Subnets.hostsForCidr("10.0.0.0/30"));
    }

    @Test
    void normalizesNonNetworkBaseAddress() {
        List<String> hosts = Subnets.hostsForCidr("192.168.1.50/24");
        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
    }

    @Test
    void rejectsTooWideRange() {
        assertThrows(IllegalArgumentException.class, () -> Subnets.hostsForCidr("10.0.0.0/8"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Subnets.hostsForCidr("not-an-ip/24"));
        assertThrows(IllegalArgumentException.class, () -> Subnets.hostsForCidr("10.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Subnets.hostsForCidr("10.0.0.999/24"));
    }

    @Test
    void ipToLongOrdersAddresses() {
        assertTrue(Subnets.ipToLong("192.168.1.2") > Subnets.ipToLong("192.168.1.1"));
        assertTrue(Subnets.ipToLong("10.0.0.1") < Subnets.ipToLong("192.168.0.1"));
    }
}
