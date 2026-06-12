package io.hivekeeper.core.model;

/**
 * Where to reach a device — connection coordinates and a {@code credRef}, never the secret itself. The
 * {@code credRef} is an opaque pointer (a label) that an on-prem {@link io.hivekeeper.core.spi.CredentialProvider}
 * resolves locally to the actual username/password; {@code null} means "use the provider's default". This
 * separation is what lets a cloud control plane dispatch work by reference without ever holding an AP secret.
 */
public record DeviceRef(DeviceId id, String host, int port, String credRef) {

    public DeviceRef {
        if (id == null) throw new IllegalArgumentException("id required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("invalid port: " + port);
    }

    /** Convenience for the common case: SSH on port 22, DeviceId derived from the host, default credential. */
    public static DeviceRef ssh(String host) {
        return new DeviceRef(DeviceId.of(host), host, 22, null);
    }

    public static DeviceRef ssh(String host, int port) {
        return new DeviceRef(DeviceId.of(host), host, port, null);
    }

    /** SSH with an explicit credential reference the on-prem provider resolves locally. */
    public static DeviceRef ssh(String host, int port, String credRef) {
        return new DeviceRef(DeviceId.of(host), host, port, credRef);
    }
}
