package io.hivekeeper.agent;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import java.util.Optional;

/**
 * The simplest on-prem credential provider: returns the same configured username/password for any device,
 * ignoring the credential reference. Adequate for a homelab where one credential covers the fleet. The key
 * property is that resolution happens HERE, on the agent — the cloud never sees the secret. See
 * {@link VaultCredentialProvider} for per-device resolution by {@code credRef}.
 */
final class DefaultCredentialProvider implements CredentialProvider {

    private final Credentials credentials;

    DefaultCredentialProvider(String user, String password) {
        this.credentials = new Credentials(user, password);
    }

    @Override
    public Optional<Credentials> resolve(DeviceRef device) {
        return Optional.of(credentials);
    }
}
