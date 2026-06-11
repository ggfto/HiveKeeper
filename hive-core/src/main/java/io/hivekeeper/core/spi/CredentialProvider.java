package io.hivekeeper.core.spi;

import io.hivekeeper.core.model.DeviceId;
import java.util.Optional;

/**
 * Resolves device credentials locally (from a keystore, env, or CLI flags). Keeps secrets on-prem.
 * In the cloud topology, this is the seam that ensures the control plane never holds an AP password —
 * the agent resolves credentials locally, keyed by {@link DeviceId}.
 */
@FunctionalInterface
public interface CredentialProvider {
    Optional<Credentials> resolve(DeviceId deviceId);
}
