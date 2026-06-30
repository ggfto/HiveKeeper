package io.hivekeeper.core.crypto;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link PskGenerator}: length, the unambiguous charset, deterministic output with a seeded RNG, and
 *  the WPA passphrase-length guard. */
class PskGeneratorTest {

    @Test
    void generatesTheRequestedLengthFromTheUnambiguousCharset() {
        String psk = new PskGenerator(new Random(42)).generate(24);
        assertEquals(24, psk.length());
        // No visually ambiguous characters (0/O, 1/l/I) so it can be read aloud or typed.
        assertFalse(psk.matches(".*[0O1lI].*"), "PSK must avoid ambiguous chars: " + psk);
        assertTrue(psk.matches("[a-zA-Z2-9]+"), "PSK must be alphanumeric (no symbols, no 0/1): " + psk);
    }

    @Test
    void isDeterministicForAGivenSeedAndDiffersAcrossSeeds() {
        assertEquals(new PskGenerator(new Random(7)).generate(20), new PskGenerator(new Random(7)).generate(20));
        assertFalse(new PskGenerator(new Random(7)).generate(20).equals(new PskGenerator(new Random(8)).generate(20)));
    }

    @Test
    void defaultsToATwentyCharKey() {
        assertEquals(20, new PskGenerator(new Random(1)).generate().length());
    }

    @Test
    void rejectsLengthsOutsideTheWpaPassphraseRange() {
        PskGenerator gen = new PskGenerator(new Random(1));
        assertThrows(IllegalArgumentException.class, () -> gen.generate(7));
        assertThrows(IllegalArgumentException.class, () -> gen.generate(64));
        // Bounds are inclusive and accepted.
        assertEquals(8, gen.generate(8).length());
        assertEquals(63, gen.generate(63).length());
    }
}
