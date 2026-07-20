package io.hivekeeper.core.spi;

import io.hivekeeper.core.crypto.CredentialPayload;

/**
 * Recovers a plaintext secret from a sealed token (an {@link io.hivekeeper.core.crypto.EnvelopeCipher}
 * {@code env1:} envelope, or a {@code plain1:} dev token). Only the on-prem agent — holding the private key
 * the secret was sealed to — can implement this; it is the seam that keeps the cloud unable to read a secret
 * it forwarded.
 *
 * <p>Raw bytes are the primitive because not every sealed secret is a credential: a git token for the backup
 * destination is a single opaque string. {@link #unseal} stays as the convenience for the credential case.
 */
@FunctionalInterface
public interface SecretUnsealer {

    /** Unseals the token into its raw bytes. Throws if it is malformed, tampered, or sealed to another key. */
    byte[] unsealRaw(String sealedToken);

    /** Unseals a {@link CredentialPayload}-shaped secret into credentials. */
    default Credentials unseal(String sealedToken) {
        return CredentialPayload.decode(unsealRaw(sealedToken));
    }
}
