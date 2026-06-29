package io.hivekeeper.core.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCipherTest {

    // Two distinct, valid 32-byte (256-bit) base64 keys.
    private static final String KEY_A = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";
    private static final String KEY_B = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final SecretCipher cipher = SecretCipher.fromBase64(KEY_A);

    @Test
    void roundTripsPlaintext() {
        String secret = "{\"spec\":{\"passphrase\":\"hunter2-very-secret\"}}";
        assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)));
    }

    @Test
    void tokenIsPrefixedAndDoesNotLeakPlaintext() {
        String token = cipher.encrypt("ascii-key hunter2-very-secret");

        assertTrue(token.startsWith("gcm1:"), token);
        assertFalse(token.contains("hunter2"), "ciphertext token must not contain the plaintext secret");
    }

    @Test
    void encryptingTheSameValueTwiceYieldsDifferentTokens() {
        // A fresh random nonce per call means the store never reveals that two records share a secret.
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String token = cipher.encrypt("sensitive");
        // Flip a character in the base64 body; GCM's tag check must reject it rather than return garbage.
        int i = token.length() - 5;
        char c = token.charAt(i);
        String tampered = token.substring(0, i) + (c == 'A' ? 'B' : 'A') + token.substring(i + 1);

        assertThrows(RuntimeException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void aTokenFromAnotherKeyIsRejected() {
        String token = SecretCipher.fromBase64(KEY_B).encrypt("sensitive");
        assertThrows(RuntimeException.class, () -> cipher.decrypt(token));
    }

    @Test
    void plaintextWithoutThePrefixIsRejectedNotPassedThrough() {
        // No silent downgrade: a value that was never encrypted must not be accepted as cleartext.
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("just a plain string"));
    }

    @Test
    void isEncryptedRecognizesOnlyTokens() {
        assertTrue(SecretCipher.isEncrypted(cipher.encrypt("x")));
        assertFalse(SecretCipher.isEncrypted("plain"));
        assertFalse(SecretCipher.isEncrypted(null));
    }

    @Test
    void weakOrMalformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64(""));
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64(null));
        // 16 bytes (AES-128) is below our 256-bit requirement.
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64("AAAAAAAAAAAAAAAAAAAAAA=="));
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64("not valid base64!!!"));
    }
}
