package io.hivekeeper.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Authenticated at-rest encryption for secret-bearing blobs. AES-256-GCM gives confidentiality AND
 * integrity: a tampered or truncated ciphertext fails to decrypt rather than yielding garbage. A fresh
 * random 96-bit nonce per encryption means encrypting the same plaintext twice produces different tokens,
 * so the store never reveals that two records share a secret.
 *
 * <p>Token format: {@code "gcm1:" + base64(nonce[12] || ciphertext || tag[16])}. The version prefix lets
 * the format evolve and makes {@link #decrypt} strict — a value without the prefix is rejected rather than
 * silently treated as plaintext (no downgrade).
 *
 * <p>This is symmetric (single-key) encryption: whoever holds the key can decrypt. The gateway uses it to
 * protect persisted job blobs at rest; the agent uses it to protect its on-disk credential vault. For
 * encrypting a secret <em>to</em> a specific recipient that the encryptor cannot itself read, see
 * {@link EnvelopeCipher}.
 */
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "gcm1:";
    private static final int KEY_BYTES = 32;      // AES-256
    private static final int NONCE_BYTES = 12;    // 96-bit nonce is the GCM standard/optimum
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private SecretCipher(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Builds a cipher from a base64-encoded 256-bit key. Throws if the key is missing or not exactly 32
     * bytes — a weak or absent key is a fail-fast configuration error, never a silent fallback.
     */
    public static SecretCipher fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("crypto key is required (set hivekeeper.crypto.key / HIVEKEEPER_CRYPTO_KEY)");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("crypto key must be valid base64", e);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "crypto key must be " + KEY_BYTES + " bytes (256-bit) base64; got " + keyBytes.length);
        }
        return new SecretCipher(keyBytes);
    }

    /** Encrypts plaintext into a self-describing {@code gcm1:} token. */
    public String encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /**
     * Decrypts a {@code gcm1:} token. Throws {@link IllegalArgumentException} if the token is not in the
     * expected format and {@link javax.crypto.AEADBadTagException} (wrapped) if it was tampered with or
     * was produced by a different key.
     */
    public String decrypt(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not an encrypted token (missing " + PREFIX + " prefix)");
        }
        byte[] buf;
        try {
            buf = Base64.getDecoder().decode(token.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encrypted token is not valid base64", e);
        }
        if (buf.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("encrypted token is too short to contain a nonce + ciphertext");
        }
        byte[] nonce = Arrays.copyOfRange(buf, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(buf, NONCE_BYTES, buf.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException (tamper / wrong key) and any other crypto failure land here.
            throw new IllegalStateException("decryption failed: ciphertext rejected", e);
        }
    }

    /** True if {@code value} is a {@code gcm1:} token produced by {@link #encrypt}. Null-safe. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
