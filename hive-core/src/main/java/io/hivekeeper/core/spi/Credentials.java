package io.hivekeeper.core.spi;

/**
 * SSH credentials for a device. SENSITIVE: resolved on-prem by a {@link CredentialProvider} and never
 * serialized to a cloud control plane. hive-wire deliberately does not serialize this type. The
 * {@code toString} masks the password so it cannot leak into logs.
 */
public record Credentials(String username, String password) {

    public Credentials {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required");
        }
    }

    @Override
    public String toString() {
        return "Credentials[username=" + username + ", password=***]";
    }
}
