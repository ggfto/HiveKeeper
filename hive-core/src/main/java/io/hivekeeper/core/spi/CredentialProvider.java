package io.hivekeeper.core.spi;

import io.hivekeeper.core.model.DeviceRef;
import java.util.Optional;

/**
 * Resolves device credentials locally (from a keystore, env, or CLI flags). Keeps secrets on-prem.
 * In the cloud topology, this is the seam that ensures the control plane never holds an AP password — the
 * agent resolves credentials locally from the {@link DeviceRef} (its {@code credRef} pointer, falling back
 * to a default), never from anything the cloud sent as a secret.
 */
@FunctionalInterface
public interface CredentialProvider {
    Optional<Credentials> resolve(DeviceRef device);
}
