package io.hivekeeper.agent;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heart of the per-device credential vault: secret resolution that happens ON the agent. The cloud only
 * ever sends a {@code credRef}; this maps it to the local username/password (or falls back to a default), so
 * a regression here would either leak the wrong credential at a device or take the whole agent down.
 */
class VaultCredentialProviderTest {

    private static final Credentials LAB = new Credentials("admin", "aerohive");
    private static final Credentials DEFAULT = new Credentials("default-user", "default-pw");

    private static DeviceRef host(String credRef) {
        return DeviceRef.ssh("10.0.0.1", 22, credRef);
    }

    @Test
    void resolvesTheMappedCredentialForAKnownCredRef() {
        var vault = new VaultCredentialProvider(Map.of("lab-ap", LAB), DEFAULT);
        Credentials c = vault.resolve(host("lab-ap")).orElseThrow();
        assertEquals("admin", c.username());
        assertEquals("aerohive", c.password());
    }

    @Test
    void anUnknownCredRefFallsBackToTheDefaultRatherThanFailing() {
        var vault = new VaultCredentialProvider(Map.of("lab-ap", LAB), DEFAULT);
        assertEquals(DEFAULT, vault.resolve(host("not-in-vault")).orElseThrow());
    }

    @Test
    void aNullCredRefReturnsTheDefault() {
        var vault = new VaultCredentialProvider(Map.of("lab-ap", LAB), DEFAULT);
        assertEquals(DEFAULT, vault.resolve(DeviceRef.ssh("10.0.0.1")).orElseThrow());
    }

    @Test
    void withNoDefaultAnUnknownOrNullCredRefResolvesToEmpty() {
        var vault = new VaultCredentialProvider(Map.of("lab-ap", LAB), null);
        assertTrue(vault.resolve(host("not-in-vault")).isEmpty());
        assertTrue(vault.resolve(DeviceRef.ssh("10.0.0.1")).isEmpty());
        // a KNOWN ref still resolves even with no default
        assertEquals(LAB, vault.resolve(host("lab-ap")).orElseThrow());
    }

    @Test
    void theVaultIsDefensivelyCopiedFromTheSourceMap() {
        Map<String, Credentials> source = new HashMap<>();
        source.put("lab-ap", LAB);
        var vault = new VaultCredentialProvider(source, null);
        // mutating the source after construction must not change what the vault resolves
        source.put("lab-ap", new Credentials("evil", "x"));
        source.put("added-later", new Credentials("z", "y"));
        assertEquals("admin", vault.resolve(host("lab-ap")).orElseThrow().username());
        assertTrue(vault.resolve(host("added-later")).isEmpty());
    }

    @Test
    void fromFileLoadsEachRefAndIgnoresNonDottedAndLeadingDotKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        Files.writeString(file, """
                lab-ap.user=admin
                lab-ap.password=aerohive
                branch-2.user=ops
                branch-2.password=secret2
                garbage=ignored
                .user=ignored
                """);
        var vault = VaultCredentialProvider.fromFile(file, DEFAULT);
        assertEquals("admin", vault.resolve(host("lab-ap")).orElseThrow().username());
        assertEquals("secret2", vault.resolve(host("branch-2")).orElseThrow().password());
        // the non-dotted and leading-dot keys produced no entries -> fall back to the default
        assertEquals(DEFAULT, vault.resolve(host("garbage")).orElseThrow());
    }

    @Test
    void fromFileSkipsAMalformedEntryInsteadOfCrashingTheAgent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        // 'lab-ap' has a password but NO user line (a common operator typo). The whole load must still
        // succeed and start the agent; only that one credRef degrades to the default.
        Files.writeString(file, """
                lab-ap.password=orphan
                branch-2.user=ops
                branch-2.password=secret2
                """);
        var vault = VaultCredentialProvider.fromFile(file, DEFAULT);
        assertEquals(DEFAULT, vault.resolve(host("lab-ap")).orElseThrow());
        assertEquals("ops", vault.resolve(host("branch-2")).orElseThrow().username());
    }

    @Test
    void fromFileThrowsWhenTheVaultFileIsMissing(@TempDir Path dir) {
        assertThrows(IOException.class, () -> VaultCredentialProvider.fromFile(dir.resolve("nope.properties"), DEFAULT));
    }
}
