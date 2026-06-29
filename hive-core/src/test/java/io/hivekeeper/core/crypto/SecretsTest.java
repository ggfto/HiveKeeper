package io.hivekeeper.core.crypto;

import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsTest {

    @Test
    void masksAsciiKeyAndPasswordTokens() {
        assertEquals("security-object HK security protocol-suite wpa2-aes-psk ascii-key ***",
                Secrets.redact("security-object HK security protocol-suite wpa2-aes-psk ascii-key hunter2"));
        assertEquals("hive lab password ***", Secrets.redact("hive lab password s3cr3t"));
    }

    @Test
    void leavesNonSecretLinesAndNullsUntouched() {
        assertEquals("interface wifi0 ssid HK", Secrets.redact("interface wifi0 ssid HK"));
        assertEquals(null, Secrets.redact(null));
    }

    @Test
    void redactsSecretsInsideAConfigAppliedResult() {
        Result.ConfigApplied applied = new Result.ConfigApplied(UUID.randomUUID(), DeviceId.of("ap"),
                List.of("security-object HK security protocol-suite wpa2-aes-psk ascii-key hunter2",
                        "ssid HK security-object HK"),
                List.of("security-object HK ... ascii-key hunter2", ""),
                true);

        Result.ConfigApplied red = (Result.ConfigApplied) Secrets.redactResult(applied);

        assertTrue(red.commands().stream().noneMatch(c -> c.contains("hunter2")));
        assertTrue(red.outputs().stream().noneMatch(o -> o.contains("hunter2")));
        assertTrue(red.commands().contains("ssid HK security-object HK"));   // structure preserved
        assertTrue(red.saved());
    }

    @Test
    void leavesNonConfigResultsUnchanged() {
        Result.Discovered discovered = new Result.Discovered(UUID.randomUUID(), DeviceId.of("net"), List.of());
        assertSame(discovered, Secrets.redactResult(discovered));
    }

    @Test
    void redactionIsThoroughEvenWithMultipleSecretsOnOneLine() {
        String line = "password aaa and ascii-key bbb";
        String red = Secrets.redact(line);
        assertFalse(red.contains("aaa"));
        assertFalse(red.contains("bbb"));
    }

    @Test
    void doesNotMaskWhenTheKeywordIsOnlyASubstring() {
        // The word boundary keeps us from mangling SSIDs/values that merely contain the keyword text.
        assertEquals("interface wifi0 ssid mypassword-net", Secrets.redact("interface wifi0 ssid mypassword-net"));
    }

    @Test
    void overMasksBenignKeywordLinesByDesignFailingSafe() {
        // Documented tradeoff: we mask the token after the keyword unconditionally. Over-masking a benign
        // word is acceptable; leaking a secret is not. Pin the behavior so a future change is a decision.
        assertEquals("password ***", Secrets.redact("password expired"));
        assertEquals("ascii-key *** 63", Secrets.redact("ascii-key length 63"));
    }

    @Test
    void preservesNonSecretConfigAppliedOutputLines() {
        Result.ConfigApplied applied = new Result.ConfigApplied(UUID.randomUUID(), DeviceId.of("ap"),
                List.of("ssid HK security-object HK"), List.of("interface wifi0 ssid HK applied"), true);

        Result.ConfigApplied red = (Result.ConfigApplied) Secrets.redactResult(applied);

        // A line with no secret keyword must pass through byte-for-byte.
        assertEquals("interface wifi0 ssid HK applied", red.outputs().get(0));
    }
}
