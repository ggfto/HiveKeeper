package io.hivekeeper.core.crypto;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeCipherTest {

    private final EnvelopeCipher cipher = new EnvelopeCipher();

    // RSA-2048 matches the keys scripts/gen-dev-pki.ps1 generates for the agent's mTLS cert.
    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    @Test
    void sealsAndUnsealsToTheRecipientKey() throws Exception {
        KeyPair kp = rsa();
        byte[] secret = "{\"username\":\"admin\",\"password\":\"aerohive\"}".getBytes(StandardCharsets.UTF_8);

        String token = cipher.seal(kp.getPublic(), secret);

        assertTrue(token.startsWith("env1:"), token);
        assertFalse(new String(java.util.Base64.getDecoder().decode(token.substring("env1:".length())),
                StandardCharsets.ISO_8859_1).contains("aerohive"), "envelope must not leak the plaintext");
        assertArrayEquals(secret, cipher.unseal(kp.getPrivate(), token));
    }

    @Test
    void aDifferentPrivateKeyCannotUnseal() throws Exception {
        String token = cipher.seal(rsa().getPublic(), "secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> cipher.unseal(rsa().getPrivate(), token));
    }

    @Test
    void tamperedEnvelopeIsRejected() throws Exception {
        KeyPair kp = rsa();
        String token = cipher.seal(kp.getPublic(), "secret".getBytes(StandardCharsets.UTF_8));
        int i = token.length() - 4;
        char c = token.charAt(i);
        String tampered = token.substring(0, i) + (c == 'A' ? 'B' : 'A') + token.substring(i + 1);
        assertThrows(RuntimeException.class, () -> cipher.unseal(kp.getPrivate(), tampered));
    }

    @Test
    void nullRecipientFallsBackToAPlainDevTokenThatStillRoundTrips() throws Exception {
        byte[] secret = "dev-secret".getBytes(StandardCharsets.UTF_8);
        String token = cipher.seal(null, secret);

        assertTrue(token.startsWith("plain1:"), token);
        assertFalse(EnvelopeCipher.isSealed(token));
        // A plain1: token decodes without any key (and a key is harmless if supplied).
        assertArrayEquals(secret, cipher.unseal(null, token));
    }

    @Test
    void isSealedOnlyForRealEnvelopes() throws Exception {
        assertTrue(EnvelopeCipher.isSealed(cipher.seal(rsa().getPublic(), "x".getBytes())));
        assertFalse(EnvelopeCipher.isSealed(cipher.seal(null, "x".getBytes())));
        assertFalse(EnvelopeCipher.isSealed(null));
    }

    @Test
    void anEnv1TokenWithoutAPrivateKeyIsRejected() throws Exception {
        String token = cipher.seal(rsa().getPublic(), "x".getBytes());
        assertThrows(IllegalArgumentException.class, () -> cipher.unseal(null, token));
    }

    @Test
    void aMalformedTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> cipher.unseal(null, "garbage-no-prefix"));
        assertThrows(IllegalArgumentException.class, () -> cipher.unseal(null, null));
    }
}
