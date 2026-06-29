package io.hivekeeper.core.spi;

/**
 * A {@link CredentialProvider} that can also be written to at runtime — the seam that turns the on-prem
 * credential vault from a boot-time, hand-edited file into something HiveKeeper can manage from the UI.
 * Implementations persist the new secret locally (encrypted at rest) so it survives a restart and is
 * resolved by {@link #resolve} for the matching {@code credRef}. The cloud never calls this directly: it
 * sends a {@code SetCredential} command and the on-prem engine performs the write.
 */
public interface WritableCredentialProvider extends CredentialProvider {

    /** Stores (or replaces) the credential for {@code credRef} and persists it locally, encrypted at rest. */
    void store(String credRef, Credentials credentials);
}
