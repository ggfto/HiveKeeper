package io.hivekeeper.core.drivers;

/**
 * Raw CLI text captured for inventory, keyed by the HiveOS show-commands that actually carry the
 * fields we need (confirmed against a live AP230): {@code show version} (firmware, uptime, platform),
 * {@code show hw-info} (serial, product name), {@code show interface mgt0} (management IP), and
 * {@code show station} (associated clients).
 */
public record HiveOsCapture(String showVersion, String showHwInfo, String showInterfaceMgt0, String showStation) {
}
