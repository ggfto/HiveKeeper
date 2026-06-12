package io.hivekeeper.core.model;

/**
 * A vendor-neutral request to configure (or remove) a WPA2-PSK SSID, optionally on a VLAN. A
 * {@link io.hivekeeper.core.drivers.Driver} translates this into its own CLI. Secrets ({@code passphrase})
 * are sent to the device by the on-prem engine/agent and never persisted by the cloud.
 */
public record SsidSpec(String name, String passphrase, Integer vlan, boolean remove) {

    public SsidSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ssid name required");
        }
        if (!remove && (passphrase == null || passphrase.isBlank())) {
            throw new IllegalArgumentException("passphrase required to create an SSID");
        }
    }

    public static SsidSpec create(String name, String passphrase, Integer vlan) {
        return new SsidSpec(name, passphrase, vlan, false);
    }

    public static SsidSpec removal(String name) {
        return new SsidSpec(name, null, null, true);
    }
}
