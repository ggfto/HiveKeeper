package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-device credential vault on the agent — now writable so HiveKeeper can manage credentials from the
 * UI. Resolution maps a {@code credRef} to the local secret (or a default); writes persist to disk encrypted
 * at rest when a vault key is configured. A regression here would leak the wrong credential, take the agent
 * down on load, or write a secret in the clear.
 */
class WritableVaultCredentialProviderTest {

    // A valid 32-byte base64 AES-256 key for at-rest encryption.
    private static final String VAULT_KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";
    private static final Credentials DEFAULT = new Credentials("default-user", "default-pw");

    private static DeviceRef host(String credRef) {
        return DeviceRef.ssh("10.0.0.1", 22, credRef);
    }

    @Test
    void resolvesAMappedCredentialAndFallsBackForUnknownOrNullRefs(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        Files.writeString(file, "lab-ap.user=admin\nlab-ap.password=aerohive\n");
        var vault = WritableVaultCredentialProvider.fromFile(file, DEFAULT, null);

        assertEquals("aerohive", vault.resolve(host("lab-ap")).orElseThrow().password());
        assertEquals(DEFAULT, vault.resolve(host("not-in-vault")).orElseThrow());
        assertEquals(DEFAULT, vault.resolve(DeviceRef.ssh("10.0.0.1")).orElseThrow());
    }

    @Test
    void withNoDefaultAnUnknownRefResolvesToEmptyButAKnownRefStillResolves(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        Files.writeString(file, "lab-ap.user=admin\nlab-ap.password=aerohive\n");
        var vault = WritableVaultCredentialProvider.fromFile(file, null, null);

        assertTrue(vault.resolve(host("not-in-vault")).isEmpty());
        assertEquals("admin", vault.resolve(host("lab-ap")).orElseThrow().username());
    }

    @Test
    void aMissingFileStartsEmptyRatherThanThrowing(@TempDir Path dir) throws IOException {
        var vault = WritableVaultCredentialProvider.fromFile(dir.resolve("nope.properties"), DEFAULT, null);
        assertEquals(DEFAULT, vault.resolve(host("anything")).orElseThrow());
    }

    @Test
    void fromFileSkipsAMalformedEntryInsteadOfCrashing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        Files.writeString(file, "lab-ap.password=orphan\nbranch-2.user=ops\nbranch-2.password=secret2\n");
        var vault = WritableVaultCredentialProvider.fromFile(file, DEFAULT, null);

        assertEquals(DEFAULT, vault.resolve(host("lab-ap")).orElseThrow());     // no .user -> default
        assertEquals("ops", vault.resolve(host("branch-2")).orElseThrow().username());
    }

    @Test
    void storePersistsTheCredentialAndItReloads(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        var vault = WritableVaultCredentialProvider.fromFile(file, DEFAULT, null);

        vault.store("dev-1", new Credentials("admin", "newpass"));

        // resolves immediately
        assertEquals("newpass", vault.resolve(host("dev-1")).orElseThrow().password());
        // and survives a reload from disk
        var reloaded = WritableVaultCredentialProvider.fromFile(file, DEFAULT, null);
        assertEquals("newpass", reloaded.resolve(host("dev-1")).orElseThrow().password());
    }

    @Test
    void withAVaultKeyThePasswordIsEncryptedOnDiskButResolvesInTheClear(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        SecretCipher cipher = SecretCipher.fromBase64(VAULT_KEY);
        var vault = WritableVaultCredentialProvider.fromFile(file, DEFAULT, cipher);

        vault.store("dev-1", new Credentials("admin", "topsecret"));

        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains("topsecret"), "the password must not be on disk in plaintext");
        // Properties.store escapes ':' in values, so the token appears as 'gcm1\:...' on disk; the prefix proves
        // it is an encrypted token (Properties.load unescapes it back to 'gcm1:' on reload).
        assertTrue(onDisk.contains("gcm1"), "the stored password must be an encrypted token");

        // A new instance with the same key decrypts it back to plaintext for resolution.
        var reloaded = WritableVaultCredentialProvider.fromFile(file, DEFAULT, cipher);
        assertEquals("topsecret", reloaded.resolve(host("dev-1")).orElseThrow().password());
    }

    @Test
    void anEncryptedEntryIsSkippedWhenNoVaultKeyIsConfigured(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vault.properties");
        SecretCipher cipher = SecretCipher.fromBase64(VAULT_KEY);
        WritableVaultCredentialProvider.fromFile(file, DEFAULT, cipher).store("dev-1", new Credentials("admin", "x"));

        // Reload WITHOUT the key: the gcm1: value cannot be decrypted, so the entry is skipped (falls back).
        var noKey = WritableVaultCredentialProvider.fromFile(file, DEFAULT, null);
        assertEquals(DEFAULT, noKey.resolve(host("dev-1")).orElseThrow());
    }
}
