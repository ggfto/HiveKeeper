package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.CredentialPayload;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.spi.Credentials;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The agent end of the sealed-credential channel: a secret the gateway seals to the agent's public key is
 *  recovered with the agent's private key, and only that key. */
class KeystoreSecretUnsealerTest {

    private final EnvelopeCipher envelope = new EnvelopeCipher();

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    @Test
    void unsealsACredentialSealedToTheAgentKey() throws Exception {
        KeyPair agent = rsa();
        // The gateway side: seal username+password to the agent's public key.
        String token = envelope.seal(agent.getPublic(), CredentialPayload.encode("admin", "aerohive"));

        var unsealer = new KeystoreSecretUnsealer(agent.getPrivate(), envelope);
        Credentials creds = unsealer.unseal(token);

        assertEquals("admin", creds.username());
        assertEquals("aerohive", creds.password());
    }

    @Test
    void aTokenSealedToAnotherKeyIsRejected() throws Exception {
        String token = envelope.seal(rsa().getPublic(), CredentialPayload.encode("admin", "x"));
        var unsealer = new KeystoreSecretUnsealer(rsa().getPrivate(), envelope);
        assertThrows(RuntimeException.class, () -> unsealer.unseal(token));
    }

    @Test
    void withoutAPrivateKeyOnlyPlainDevTokensWork() {
        var unsealer = new KeystoreSecretUnsealer(null, envelope);
        // plain1: dev token (no recipient) round-trips...
        String plain = envelope.seal(null, CredentialPayload.encode("admin", "devpw"));
        assertEquals("devpw", unsealer.unseal(plain).password());
        // ...but a real env1: envelope cannot be unsealed without the key.
        // (sealing needs a key, so just assert a well-formed env1 prefix is refused)
        assertThrows(RuntimeException.class, () -> unsealer.unseal("env1:AAAA"));
    }
}
