package io.hivekeeper.core.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure IPv4/CIDR helpers for discovery. No network I/O here (so it is fully unit-testable). */
public final class Subnets {

    /** Refuse sweeps wider than this prefix (a /22 is ~1022 hosts) to avoid accidental huge scans. */
    private static final int MIN_PREFIX = 22;

    private Subnets() {
    }

    /** Expands a CIDR like {@code 192.168.1.0/24} into its usable host addresses. */
    public static List<String> hostsForCidr(String cidr) {
        if (cidr == null) {
            throw new IllegalArgumentException("cidr is null");
        }
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected a.b.c.d/prefix, got: " + cidr);
        }
        long ip = ipToLong(parts[0]);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid prefix: " + parts[1]);
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("prefix out of range: " + prefix);
        }
        if (prefix < MIN_PREFIX) {
            throw new IllegalArgumentException(
                    "range too large; use /" + MIN_PREFIX + " or more specific (got /" + prefix + ")");
        }

        long mask = maskFor(prefix);
        long network = ip & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);

        List<String> hosts = new ArrayList<>();
        if (prefix == 32) {
            hosts.add(longToIp(network));
        } else if (prefix == 31) {
            hosts.add(longToIp(network));
            hosts.add(longToIp(broadcast));
        } else {
            for (long a = network + 1; a < broadcast; a++) {
                hosts.add(longToIp(a));
            }
        }
        return hosts;
    }

    /** Best-effort primary IPv4 network of this machine as a CIDR, or {@code null} if undeterminable. */
    public static String localIpv4Cidr() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        int prefix = ia.getNetworkPrefixLength();
                        if (prefix < MIN_PREFIX || prefix > 32) {
                            prefix = 24;  // clamp odd/huge ranges to a sane /24 sweep
                        }
                        long network = ipToLong(addr.getHostAddress()) & maskFor(prefix);
                        return longToIp(network) + "/" + prefix;
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public static long ipToLong(String ip) {
        String[] octets = ip == null ? new String[0] : ip.trim().split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("invalid IPv4 address: " + ip);
        }
        long value = 0;
        for (String octet : octets) {
            int o;
            try {
                o = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid IPv4 address: " + ip);
            }
            if (o < 0 || o > 255) {
                throw new IllegalArgumentException("invalid IPv4 octet in: " + ip);
            }
            value = (value << 8) | o;
        }
        return value;
    }

    static String longToIp(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    private static long maskFor(int prefix) {
        return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }
}
