package io.hivekeeper.core.discovery;

import java.util.List;

/** Probes a set of hosts for reachability on a port. An SPI so alternative strategies (mDNS, ARP,
 *  SNMP) can be added later without changing callers. */
public interface Scanner {

    List<DiscoveryResult> scan(List<String> hosts, int port, int timeoutMillis);
}
