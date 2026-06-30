package io.hivekeeper.core.crypto;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Generates strong, human-distributable Private PSKs (WPA2/WPA3 passphrases: 8–63 ASCII chars). Pure and
 * RNG-injectable so it is unit-testable: production uses a {@link SecureRandom}; tests inject a seeded
 * {@link Random} for deterministic output. The default charset omits visually ambiguous characters
 * ({@code 0/O}, {@code 1/l/I}) so a key can be read aloud or typed without confusion.
 */
public final class PskGenerator {

    /** Unambiguous charset: no 0/O, 1/l/I. */
    private static final String CHARSET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int DEFAULT_LENGTH = 20;
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 63;

    private final Random random;

    /** Production constructor: a cryptographically strong RNG. */
    public PskGenerator() {
        this(new SecureRandom());
    }

    /** Test/seam constructor: inject any {@link Random} (e.g. a seeded one for deterministic tests). */
    public PskGenerator(Random random) {
        this.random = random;
    }

    /** A PSK of the default length ({@value #DEFAULT_LENGTH} chars). */
    public String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /** A PSK of {@code length} chars ({@value #MIN_LENGTH}–{@value #MAX_LENGTH}, the WPA passphrase range). */
    public String generate(int length) {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "PSK length must be " + MIN_LENGTH + ".." + MAX_LENGTH + " (was " + length + ")");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARSET.charAt(random.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }
}
