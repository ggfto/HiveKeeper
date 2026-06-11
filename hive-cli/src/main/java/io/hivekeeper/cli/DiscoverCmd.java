package io.hivekeeper.cli;

import io.hivekeeper.core.discovery.DiscoveryResult;
import io.hivekeeper.core.discovery.Subnets;
import io.hivekeeper.core.discovery.TcpBannerScanner;
import picocli.CommandLine;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "discover", mixinStandardHelpOptions = true,
        description = "Sweep a subnet for SSH-reachable hosts (finds APs without knowing their IPs).")
final class DiscoverCmd implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "CIDR",
            description = "Subnet to scan, e.g. 192.168.1.0/24 (defaults to this machine's local subnet)")
    String cidr;

    @CommandLine.Option(names = {"-P", "--port"}, defaultValue = "22",
            description = "TCP port to probe (default: 22)")
    int port;

    @CommandLine.Option(names = "--timeout", defaultValue = "800",
            description = "Per-host connect/read timeout in ms (default: 800)")
    int timeoutMillis;

    @Override
    public Integer call() {
        String range = (cidr != null && !cidr.isBlank()) ? cidr : Subnets.localIpv4Cidr();
        if (range == null) {
            System.err.println("Could not determine the local subnet; pass one explicitly, e.g. 192.168.1.0/24");
            return 2;
        }

        List<String> hosts;
        try {
            hosts = Subnets.hostsForCidr(range);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid range: " + e.getMessage());
            return 2;
        }

        System.err.printf("Scanning %s (%d hosts) on port %d ...%n", range, hosts.size(), port);
        List<DiscoveryResult> reachable = new TcpBannerScanner().scan(hosts, port, timeoutMillis).stream()
                .filter(DiscoveryResult::reachable)
                .sorted(Comparator.comparingLong(r -> Subnets.ipToLong(r.host())))
                .toList();

        for (DiscoveryResult r : reachable) {
            String banner = r.sshBanner() == null ? "(open, no banner)" : r.sshBanner();
            String tag = r.looksLikeSsh() ? "  [ssh]" : "";
            System.out.printf("%-15s  %s%s%n", r.host(), banner, tag);
        }
        System.out.printf("%d reachable on :%d of %d scanned.%n", reachable.size(), port, hosts.size());
        return 0;
    }
}
