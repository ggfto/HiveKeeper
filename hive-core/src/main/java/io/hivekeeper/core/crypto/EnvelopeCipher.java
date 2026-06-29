package io.hivekeeper.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Hybrid (envelope) encryption that lets a sender encrypt a secret <em>to</em> a recipient it cannot
 * itself decrypt. The gateway uses this to seal an operator-typed device credential to the on-prem
 * agent's RSA public key (taken from its mTLS client certificate): the gateway holds no key able to
 * recover the plaintext, and only the agent — with its keystore private key — can {@link #unseal} it.
 * This is the north-star "secrets never persist in the cloud in a form the cloud can read".
 *
 * <p>Scheme: a fresh random AES-256 key encrypts the payload with AES-GCM (a 96-bit nonce, 128-bit tag);
 * that AES key is wrapped with RSA-OAEP (SHA-256). Token:
 * {@code "env1:" + base64( u16 wrappedKeyLen || wrappedKey || nonce[12] || ciphertext||tag )}.
 *
 * <p>Dev fallback: when no recipient key is available (a dev agent connected over {@code ws://} presents
 * no certificate), {@link #seal(PublicKey, byte[])} with a {@code null} recipient emits a clearly-marked
 * {@code "plain1:"} token (base64 of the plaintext, NOT encrypted) and the caller is expected to log a
 * warning. {@link #unseal} accepts both {@code env1:} and {@code plain1:}. Never rely on {@code plain1:}
 * in production — it exists only so a keyless local setup keeps working.
 */
public final class EnvelopeCipher {

    private static final String ENV_PREFIX = "env1:";
    private static final String PLAIN_PREFIX = "plain1:";
    private static final String AES = "AES/GCM/NoPadding";
    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int AES_KEY_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    private static OAEPParameterSpec oaep() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    /**
     * Seals {@code plaintext} to {@code recipient}'s public key, returning an {@code env1:} token. If
     * {@code recipient} is {@code null}, returns a {@code plain1:} dev token instead (the plaintext is NOT
     * encrypted) — callers must warn when they pass null.
     */
    public String seal(PublicKey recipient, byte[] plaintext) {
        if (recipient == null) {
            return PLAIN_PREFIX + Base64.getEncoder().encodeToString(plaintext);
        }
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_BITS);
            SecretKey aesKey = kg.generateKey();

            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = aes.doFinal(plaintext);

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.ENCRYPT_MODE, recipient, oaep());
            byte[] wrappedKey = rsa.doFinal(aesKey.getEncoded());

            ByteBuffer buf = ByteBuffer.allocate(2 + wrappedKey.length + nonce.length + ciphertext.length);
            buf.putShort((short) wrappedKey.length);
            buf.put(wrappedKey);
            buf.put(nonce);
            buf.put(ciphertext);
            return ENV_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("seal failed", e);
        }
    }

    /**
     * Unseals a token produced by {@link #seal}. An {@code env1:} token is decrypted with
     * {@code recipientPrivateKey} (which must match the public key it was sealed to); a {@code plain1:}
     * dev token is decoded without a key (the key may be null). Throws on a malformed token, a wrong key,
     * or tampering (the GCM tag check fails).
     */
    public byte[] unseal(PrivateKey recipientPrivateKey, String token) {
        if (token == null) {
            throw new IllegalArgumentException("token is null");
        }
        if (token.startsWith(PLAIN_PREFIX)) {
            return Base64.getDecoder().decode(token.substring(PLAIN_PREFIX.length()));
        }
        if (!token.startsWith(ENV_PREFIX)) {
            throw new IllegalArgumentException("not a sealed token (missing " + ENV_PREFIX + " prefix)");
        }
        if (recipientPrivateKey == null) {
            throw new IllegalArgumentException("an env1: token requires a private key to unseal");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(token.substring(ENV_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sealed token is not valid base64", e);
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(raw);
            int wrappedLen = buf.getShort() & 0xFFFF;
            if (wrappedLen <= 0 || wrappedLen > buf.remaining() - NONCE_BYTES) {
                throw new IllegalArgumentException("sealed token has an invalid wrapped-key length");
            }
            byte[] wrappedKey = new byte[wrappedLen];
            buf.get(wrappedKey);
            byte[] nonce = new byte[NONCE_BYTES];
            buf.get(nonce);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.DECRYPT_MODE, recipientPrivateKey, oaep());
            byte[] aesKeyBytes = rsa.doFinal(wrappedKey);

            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return aes.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("unseal failed: token rejected", e);
        }
    }

    /** True if the token is a real (encrypted) {@code env1:} envelope rather than the {@code plain1:} dev form. */
    public static boolean isSealed(String token) {
        return token != null && token.startsWith(ENV_PREFIX);
    }
}
