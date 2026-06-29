package io.hivekeeper.core.spi;

/**
 * Recovers a plaintext {@link Credentials} from a sealed secret token (an
 * {@link io.hivekeeper.core.crypto.EnvelopeCipher} {@code env1:} envelope, or a {@code plain1:} dev token).
 * Only the on-prem agent — holding the private key the secret was sealed to — can implement this; it is the
 * seam that keeps the cloud unable to read a credential it forwarded. Injected into the local engine so
 * {@code SetCredential} can unseal locally before writing the vault.
 */
@FunctionalInterface
public interface SecretUnsealer {

    /** Unseals the token into credentials. Throws if the token is malformed, tampered, or sealed to a
     *  different key. */
    Credentials unseal(String sealedToken);
}
