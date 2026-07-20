package io.hivekeeper.core.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What one radio can see of the air around it: the cost the AP assigns each channel it may use, and the
 * neighbouring BSSIDs it heard while scanning.
 *
 * <p>The costs are the AP's own, not ours. HiveOS runs ACSP (its automatic channel selection protocol),
 * which scans and scores every permitted channel; reading that out beats re-deriving it from a neighbour
 * list, because the AP measured the air and we did not. HiveKeeper's job is to surface the number, explain
 * what drove it, and let a human decide — automatic selection already has the AP covered.
 *
 * <p>A cost of {@link #UNUSABLE_COST} is HiveOS's sentinel for "not a candidate", with a reason: on 2.4 GHz
 * every channel other than 1, 6 and 11 is marked {@code overlap}; on 5 GHz a channel that is the secondary
 * half of a wider bonded channel is marked {@code offset}. Those are not expensive channels, they are
 * channels the radio cannot centre on at its configured width, so they are excluded rather than ranked.
 */
public record ChannelScan(String iface, String state, Integer currentChannel, List<ChannelCost> channels,
                          List<Neighbor> neighbors) {

    /** HiveOS's sentinel cost for a channel that is not a candidate at all. */
    public static final int UNUSABLE_COST = 32767;

    /**
     * One candidate channel and what the AP thinks of it. {@code reason} is HiveOS's own word for why a
     * channel is unusable ({@code overlap} / {@code offset}), or null for a usable one.
     */
    public record ChannelCost(int channel, int cost, String reason) {
        public boolean usable() {
            return cost < UNUSABLE_COST;
        }
    }

    /**
     * A BSSID heard during the scan. {@code ourFleet} is HiveOS's "Aerohive AP" column — a neighbour that is
     * one of yours, which matters because your own APs cooperate over ACSP while a stranger's does not.
     * {@code utilization} (the CU column, percent) and {@code stations} are only reported for fleet
     * neighbours; a foreign AP prints {@code --} and they arrive null.
     */
    public record Neighbor(String bssid, String ssid, int channel, int rssiDbm, boolean ourFleet,
                           Integer utilization, Integer stations, String channelWidth) {
    }

    /** The usable channels, cheapest first. Empty when every channel was ruled out. */
    public List<ChannelCost> ranked() {
        return channels.stream()
                .filter(ChannelCost::usable)
                .sorted(Comparator.comparingInt(ChannelCost::cost).thenComparingInt(ChannelCost::channel))
                .toList();
    }

    /** The cheapest usable channel, if there is one. */
    public Optional<ChannelCost> best() {
        return ranked().stream().findFirst();
    }

    /** How many neighbours are sitting on a given channel — the crowding behind the cost. */
    public long neighborsOn(int channel) {
        return neighbors.stream().filter(n -> n.channel() == channel).count();
    }

    /**
     * The loudest neighbour on a channel, in dBm (closer to zero is louder), or null if the channel is clear.
     * Worth showing next to a cost: a single loud neighbour and a dozen distant ones can score similarly,
     * and they are not the same problem.
     */
    public Integer loudestOn(int channel) {
        return neighbors.stream()
                .filter(n -> n.channel() == channel)
                .mapToInt(Neighbor::rssiDbm)
                .max()
                .stream().boxed().findFirst().orElse(null);
    }
}
