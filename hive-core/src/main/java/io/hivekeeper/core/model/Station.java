package io.hivekeeper.core.model;

/** A wireless client associated to an access point. Fields are nullable where the CLI omits them. */
public record Station(
        String mac,
        String ipAddress,
        String hostname,
        String ssid,
        String osType,
        Integer rssi) {
}
