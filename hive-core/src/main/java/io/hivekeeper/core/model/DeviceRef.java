package io.hivekeeper.core.model;

/**
 * Where to reach a device — connection coordinates only, never credentials. Credentials are resolved
 * separately, on-prem, by a CredentialProvider keyed by {@link DeviceId}. This separation is what lets
 * a cloud control plane dispatch work by reference without ever holding an AP secret.
 */
public record DeviceRef(DeviceId id, String host, int port) {

    public DeviceRef {
        if (id == null) throw new IllegalArgumentException("id required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("invalid port: " + port);
    }

    /** Convenience for the common case: SSH on port 22, DeviceId derived from the host. */
    public static DeviceRef ssh(String host) {
        return new DeviceRef(DeviceId.of(host), host, 22);
    }

    public static DeviceRef ssh(String host, int port) {
        return new DeviceRef(DeviceId.of(host), host, port);
    }
}
