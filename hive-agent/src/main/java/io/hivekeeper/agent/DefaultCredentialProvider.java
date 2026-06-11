package io.hivekeeper.agent;

import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import java.util.Optional;

/**
 * v1 on-prem credential provider: returns the same configured username/password for any device.
 * Adequate for a homelab/lab where one credential covers the fleet. The key property is that resolution
 * happens HERE, on the agent — the cloud never sees these secrets. A production agent would resolve
 * per-device from a local encrypted keystore behind this same interface.
 */
final class DefaultCredentialProvider implements CredentialProvider {

    private final Credentials credentials;

    DefaultCredentialProvider(String user, String password) {
        this.credentials = new Credentials(user, password);
    }

    @Override
    public Optional<Credentials> resolve(DeviceId deviceId) {
        return Optional.of(credentials);
    }
}
