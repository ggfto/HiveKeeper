package io.hivekeeper.core.model;

/**
 * A vendor-neutral request to configure this device's hive (mesh) membership: the hive name, its shared
 * password, and the management interface that binds to it. A {@link io.hivekeeper.core.drivers.Driver}
 * translates this into its own CLI. The {@code password} is a secret — sent to the device by the on-prem
 * engine/agent and never persisted by the cloud.
 */
public record HiveSpec(String name, String password, String boundInterface) {

    /** HiveOS binds the hive on the management interface by default. */
    public static final String DEFAULT_INTERFACE = "mgt0";

    public HiveSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("hive name required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("hive password required");
        }
        if (boundInterface == null || boundInterface.isBlank()) {
            boundInterface = DEFAULT_INTERFACE;
        }
    }

    public static HiveSpec of(String name, String password) {
        return new HiveSpec(name, password, DEFAULT_INTERFACE);
    }
}
