package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.CredentialPayload;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.SecretUnsealer;
import lombok.extern.slf4j.Slf4j;
import java.security.PrivateKey;

/**
 * Unseals a credential the gateway sealed to this agent's public key, using the agent's mTLS keystore
 * private key. This is the agent-only end of the end-to-end credential channel: the gateway can encrypt
 * <em>to</em> the agent but cannot read what it forwarded, and only this class — holding the matching
 * private key — recovers the plaintext (locally, just before it is written to the vault).
 *
 * <p>When no private key is configured (a dev agent over {@code ws://} with no certificate) only the
 * {@code plain1:} dev token form can be unsealed; a real {@code env1:} envelope is rejected.
 */
@Slf4j
final class KeystoreSecretUnsealer implements SecretUnsealer {

    private final PrivateKey privateKey;    // nullable — then only plain1: dev tokens work
    private final EnvelopeCipher envelope;

    KeystoreSecretUnsealer(PrivateKey privateKey, EnvelopeCipher envelope) {
        this.privateKey = privateKey;
        this.envelope = envelope;
        if (privateKey == null) {
            log.warn("no agent private key available — only plain1: dev credential tokens can be unsealed");
        }
    }

    @Override
    public Credentials unseal(String sealedToken) {
        byte[] plaintext = envelope.unseal(privateKey, sealedToken);
        return CredentialPayload.decode(plaintext);
    }
}
