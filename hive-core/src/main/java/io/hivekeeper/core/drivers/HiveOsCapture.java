package io.hivekeeper.core.drivers;

/**
 * Raw CLI text captured for HiveOS inventory. Package-private — it is an internal detail of
 * {@link HiveOsDriver}, never part of the {@link Driver} SPI, so other vendors' drivers are unaffected
 * by HiveOS's command shape.
 */
record HiveOsCapture(String showVersion, String showHwInfo, String showInterfaceMgt0, String showStation) {
}
