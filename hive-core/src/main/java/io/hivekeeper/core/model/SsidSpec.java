package io.hivekeeper.core.model;

import java.util.Set;

/**
 * A vendor-neutral request to configure (or remove) an SSID, optionally on a VLAN. A
 * {@link io.hivekeeper.core.drivers.Driver} translates this into its own CLI. Secrets ({@code passphrase},
 * the RADIUS {@code shared-secret}) are sent to the device by the on-prem engine/agent and never persisted by
 * the cloud in the clear.
 *
 * <p>{@code security} names the protocol suite. The vendor-neutral values handled today are {@link #OPEN}
 * (no authentication, no key), {@link #WPA2_PSK} / {@link #WPA3_SAE} (preshared-key suites, which take a
 * {@code passphrase}) and the enterprise 802.1X suites {@link #WPA2_8021X} / {@link #WPA3_8021X} (which take a
 * {@link RadiusSpec} instead of a passphrase). A driver maps these to its own grammar (HiveOS uses the same
 * tokens as {@code security-object … protocol-suite …}).
 */
public record SsidSpec(String name, String passphrase, Integer vlan, boolean remove, String security,
                       RadiusSpec radius) {

    public static final String OPEN = "open";
    public static final String WPA2_PSK = "wpa2-aes-psk";
    public static final String WPA3_SAE = "wpa3-sae";
    public static final String WPA2_8021X = "wpa2-aes-8021x";
    public static final String WPA3_8021X = "wpa3-aes-8021x-std";

    /** The preshared-key suites (need a passphrase). */
    public static final Set<String> PSK_SUITES = Set.of(WPA2_PSK, WPA3_SAE);
    /** The enterprise 802.1X suites (need a RADIUS server). */
    public static final Set<String> ENTERPRISE_SUITES = Set.of(WPA2_8021X, WPA3_8021X);
    /** Every suite this model accepts. */
    public static final Set<String> SUITES =
            Set.of(OPEN, WPA2_PSK, WPA3_SAE, WPA2_8021X, WPA3_8021X);

    /** A RADIUS authentication server for the enterprise (802.1X) suites. {@code authPort} is optional. */
    public record RadiusSpec(String server, String sharedSecret, Integer authPort) {
        public RadiusSpec {
            if (server == null || server.isBlank()) {
                throw new IllegalArgumentException("RADIUS server address required");
            }
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalArgumentException("RADIUS shared secret required");
            }
        }
    }

    public SsidSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ssid name required");
        }
        // A null/blank suite means the historical default so older callers keep working.
        security = (security == null || security.isBlank()) ? WPA2_PSK : security;
        if (!remove) {
            if (!SUITES.contains(security)) {
                throw new IllegalArgumentException("unsupported security suite: " + security);
            }
            boolean enterprise = ENTERPRISE_SUITES.contains(security);
            boolean keyed = PSK_SUITES.contains(security);
            if (keyed && (passphrase == null || passphrase.isBlank())) {
                throw new IllegalArgumentException("passphrase required to create a " + security + " SSID");
            }
            if (!keyed && passphrase != null && !passphrase.isBlank()) {
                throw new IllegalArgumentException(security + " does not take a passphrase");
            }
            if (enterprise && radius == null) {
                throw new IllegalArgumentException("a RADIUS server is required for " + security);
            }
            if (!enterprise && radius != null) {
                throw new IllegalArgumentException(security + " does not take a RADIUS server");
            }
        }
    }

    /** Create a WPA2-PSK SSID (the historical default). */
    public static SsidSpec create(String name, String passphrase, Integer vlan) {
        return new SsidSpec(name, passphrase, vlan, false, WPA2_PSK, null);
    }

    /** Create a preshared-key or open SSID on the given suite. {@code open} ignores the passphrase. */
    public static SsidSpec create(String name, String passphrase, Integer vlan, String security) {
        return new SsidSpec(name, passphrase, vlan, false, security, null);
    }

    /** Create an enterprise (802.1X) SSID authenticated against a RADIUS server. */
    public static SsidSpec createEnterprise(String name, Integer vlan, String security, RadiusSpec radius) {
        return new SsidSpec(name, null, vlan, false, security, radius);
    }

    public static SsidSpec removal(String name) {
        return new SsidSpec(name, null, null, true, null, null);
    }
}
