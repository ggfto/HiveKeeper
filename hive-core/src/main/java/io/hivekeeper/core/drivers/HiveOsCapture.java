package io.hivekeeper.core.drivers;

/** Raw CLI text captured for inventory, keyed by the well-known HiveOS show-commands. */
public record HiveOsCapture(String showVersion, String showInterface, String showStation) {
}
