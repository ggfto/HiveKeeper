package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.ChannelScan;
import io.hivekeeper.core.model.DeviceRef;
import picocli.CommandLine;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "scan", mixinStandardHelpOptions = true,
        description = "Read what each radio hears around it and which channel the AP scores cheapest. Read-only.")
final class ScanChannelsCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = "--neighbors",
            description = "Also list every neighbouring AP heard, loudest first")
    boolean neighbors;

    @Override
    public Integer call() {
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.ScanChannels.of(ref), new ConsoleEventSink());
            if (result instanceof Result.ChannelsScanned scanned) {
                print(scanned.scans());
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("scan failed: " + e.getMessage());
            return 1;
        }
    }

    private void print(List<ChannelScan> scans) {
        if (scans.isEmpty()) {
            System.out.println();
            System.out.println("No scan data. The driver may not support it, or the radios are still scanning.");
            return;
        }
        for (ChannelScan scan : scans) {
            System.out.println();
            System.out.println(scan.iface() + "  (currently on channel " + orDash(scan.currentChannel())
                    + ", state " + orDash(scan.state()) + ")");

            List<ChannelScan.ChannelCost> ranked = scan.ranked();
            if (ranked.isEmpty()) {
                System.out.println("  no usable channel reported");
                continue;
            }
            System.out.printf("  %-9s %-7s %-11s %s%n", "CHANNEL", "COST", "NEIGHBOURS", "LOUDEST");
            for (ChannelScan.ChannelCost c : ranked) {
                Integer loudest = scan.loudestOn(c.channel());
                boolean here = Integer.valueOf(c.channel()).equals(scan.currentChannel());
                System.out.printf("  %-9s %-7d %-11d %s%s%n",
                        c.channel(), c.cost(), scan.neighborsOn(c.channel()),
                        loudest == null ? "-" : loudest + " dBm",
                        here ? "   <- current" : "");
            }

            ChannelScan.ChannelCost best = ranked.get(0);
            if (Integer.valueOf(best.channel()).equals(scan.currentChannel())) {
                System.out.println("  Already on the cheapest channel the AP found.");
            } else {
                System.out.println("  Suggested: channel " + best.channel() + " (cost " + best.cost()
                        + "). Changing it reconnects every client on this radio.");
            }

            long excluded = scan.channels().stream().filter(c -> !c.usable()).count();
            if (excluded > 0) {
                // Worth stating: these are not expensive channels, they are channels the radio cannot use.
                System.out.println("  " + excluded + " channel(s) excluded by the AP (overlap/bonding offset).");
            }

            if (neighbors && !scan.neighbors().isEmpty()) {
                System.out.println();
                System.out.printf("  %-24s %-16s %-4s %-9s %s%n", "SSID", "BSSID", "CH", "SIGNAL", "WIDTH");
                scan.neighbors().stream()
                        .sorted((a, b) -> Integer.compare(b.rssiDbm(), a.rssiDbm()))
                        .forEach(n -> System.out.printf("  %-24s %-16s %-4d %-9s %s%s%n",
                                n.ssid() == null ? "(hidden)" : truncate(n.ssid()),
                                n.bssid(), n.channel(), n.rssiDbm() + " dBm", n.channelWidth(),
                                n.ourFleet() ? "   (yours)" : ""));
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= 24 ? s : s.substring(0, 21) + "...";
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }
}
