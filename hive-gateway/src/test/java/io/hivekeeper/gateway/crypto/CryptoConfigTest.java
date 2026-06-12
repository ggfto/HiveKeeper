package io.hivekeeper.gateway.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the key-management decision: production (the postgres profile) must never silently encrypt under
 * the public built-in dev key. These call the bean factory method directly — no Spring context needed.
 */
class CryptoConfigTest {

    private static final String DEV_KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";
    private static final String REAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final CryptoConfig config = new CryptoConfig();

    @Test
    void failsFastWhenNoKeyAndDevKeyNotExplicitlyAllowed() {
        assertThrows(IllegalStateException.class, () -> config.secretCipher("", false));
        assertThrows(IllegalStateException.class, () -> config.secretCipher(null, false));
    }

    @Test
    void rejectsThePublicDevKeyEvenWhenSuppliedDirectly() {
        assertThrows(IllegalStateException.class, () -> config.secretCipher(DEV_KEY, false));
    }

    @Test
    void allowsTheDevKeyOnlyBehindTheExplicitOptIn() {
        assertNotNull(config.secretCipher("", true));        // no key + opt-in -> built-in dev key
        assertNotNull(config.secretCipher(DEV_KEY, true));   // dev key + opt-in -> allowed
    }

    @Test
    void acceptsARealConfiguredKey() {
        assertNotNull(config.secretCipher(REAL_KEY, false));
    }
}
