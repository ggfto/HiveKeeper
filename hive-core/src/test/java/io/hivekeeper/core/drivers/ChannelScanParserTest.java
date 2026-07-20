package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ChannelScan;
import io.hivekeeper.core.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses real {@code show acsp channel-info} / {@code show acsp neighbor} output captured from the lab
 * AP630 (HiveOS 10.6r6) on 2026-07-20 — a genuinely dense RF environment, 104 neighbours on 2.4 GHz.
 */
class ChannelScanParserTest {

    private final Map<String, ChannelScan> scans = HiveOsParser.parseChannelScans(
            Fixtures.load("/fixtures/ap630/show_acsp_channel_info.txt"),
            Fixtures.load("/fixtures/ap630/show_acsp_neighbor.txt"));

    @Test
    void readsOneScanPerRadio() {
        assertEquals(2, scans.size());
        assertTrue(scans.containsKey("wifi0"));
        assertTrue(scans.containsKey("wifi1"));
    }

    @Test
    void readsTheStateAndTheApsOwnPick() {
        ChannelScan wifi0 = scans.get("wifi0");

        assertEquals("RUN", wifi0.state());
        assertEquals(1, wifi0.currentChannel());
    }

    @Test
    void treatsTheSentinelCostAsUnusableRatherThanExpensive() {
        ChannelScan wifi0 = scans.get("wifi0");

        // On 2.4 GHz everything but 1/6/11 is marked `overlap` at cost 32767. Those are not costly
        // channels, they are channels the radio cannot centre on — ranking them would be nonsense.
        assertEquals(3, wifi0.ranked().size());
        assertEquals(java.util.List.of(1, 11, 6), wifi0.ranked().stream().map(ChannelScan.ChannelCost::channel).toList());

        ChannelScan.ChannelCost overlapping = wifi0.channels().stream()
                .filter(c -> c.channel() == 2).findFirst().orElseThrow();
        assertFalse(overlapping.usable());
        assertEquals("overlap", overlapping.reason());
    }

    @Test
    void ranksTheCheapestUsableChannelFirst() {
        // Channel 1 costs 6, channel 11 costs 20, channel 6 costs 43.
        assertEquals(1, scans.get("wifi0").best().orElseThrow().channel());
        assertEquals(6, scans.get("wifi0").best().orElseThrow().cost());
    }

    @Test
    void marksFiveGigahertzBondingOffsetsUnusableToo() {
        ChannelScan wifi1 = scans.get("wifi1");

        ChannelScan.ChannelCost offset = wifi1.channels().stream()
                .filter(c -> c.channel() == 40).findFirst().orElseThrow();
        assertFalse(offset.usable());
        assertEquals("offset", offset.reason());
        assertEquals(36, wifi1.best().orElseThrow().channel());
    }

    @Test
    void readsNeighboursIncludingSsidsThatContainSpaces() {
        ChannelScan wifi0 = scans.get("wifi0");

        // "Portaria Zenith" — a real SSID with a space in it, which is why the row cannot be split on
        // whitespace and is anchored on the BSSID and the numeric channel/RSSI pair instead.
        assertTrue(wifi0.neighbors().stream().anyMatch(n -> "Portaria Zenith".equals(n.ssid())));
    }

    @Test
    void readsAHiddenNetworkAsNoSsidRatherThanShiftingEveryColumn() {
        ChannelScan wifi0 = scans.get("wifi0");

        ChannelScan.Neighbor hidden = wifi0.neighbors().stream()
                .filter(n -> n.bssid().equals("6283:e75e:e128")).findFirst().orElseThrow();
        assertNull(hidden.ssid());
        assertEquals(1, hidden.channel());
        assertEquals(-82, hidden.rssiDbm());
        assertEquals("40+", hidden.channelWidth());
    }

    @Test
    void distinguishesOurOwnApsFromStrangers() {
        ChannelScan wifi0 = scans.get("wifi0");

        ChannelScan.Neighbor ours = wifi0.neighbors().stream()
                .filter(n -> "604_EN".equals(n.ssid()) && n.channel() == 11).findFirst().orElseThrow();
        assertTrue(ours.ourFleet());
        // Utilization and station counts are only reported for fleet neighbours.
        assertEquals(55, ours.utilization());
        assertNotNull(ours.stations());

        ChannelScan.Neighbor stranger = wifi0.neighbors().stream()
                .filter(n -> "A707".equals(n.ssid())).findFirst().orElseThrow();
        assertFalse(stranger.ourFleet());
        assertNull(stranger.utilization(), "a foreign AP prints -- for CU, which must not parse as a number");
        assertNull(stranger.stations());
    }

    @Test
    void countsCrowdingAndFindsTheLoudestNeighbourOnAChannel() {
        ChannelScan wifi0 = scans.get("wifi0");

        assertTrue(wifi0.neighborsOn(1) >= 5);
        // Loudest = closest to zero. Channel 11 holds our own AP at -27 dBm.
        assertEquals(-27, wifi0.loudestOn(11));
        assertNull(wifi0.loudestOn(13), "no neighbour there, so there is nothing to report");
    }

    @Test
    void degradesToEmptyRatherThanThrowingOnMissingOutput() {
        assertTrue(HiveOsParser.parseChannelScans(null, null).isEmpty());
        assertTrue(HiveOsParser.parseChannelScans("", "").isEmpty());
        // A radio that has scored channels but not yet heard neighbours is still a valid scan.
        Map<String, ChannelScan> noNeighbours = HiveOsParser.parseChannelScans(
                Fixtures.load("/fixtures/ap630/show_acsp_channel_info.txt"), null);
        assertEquals(2, noNeighbours.size());
        assertTrue(noNeighbours.get("wifi0").neighbors().isEmpty());
    }
}
