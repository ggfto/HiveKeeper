package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.BackupRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBackupDestinationStoreTest {

    private static SecretCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return SecretCipher.fromBase64(Base64.getEncoder().encodeToString(key));
    }

    private static BackupRemote remote() {
        return new BackupRemote("https://github.com/acme/hk-backups.git", null, "ghp_supersecret", "main");
    }

    @Test
    void startsEmptyWhenNothingHasBeenConfigured(@TempDir Path dir) {
        assertNull(FileBackupDestinationStore.fromFile(dir.resolve("dest.properties"), cipher()).get());
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("dest.properties");
        SecretCipher key = cipher();

        FileBackupDestinationStore.fromFile(file, key).set(remote());

        // A brand-new store over the same file is exactly what a restart looks like.
        BackupRemote reloaded = FileBackupDestinationStore.fromFile(file, key).get();
        assertNotNull(reloaded);
        assertEquals("https://github.com/acme/hk-backups.git", reloaded.url());
        assertEquals("ghp_supersecret", reloaded.token());
        assertEquals("main", reloaded.branch());
    }

    @Test
    void encryptsTheTokenAtRest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dest.properties");

        FileBackupDestinationStore.fromFile(file, cipher()).set(remote());

        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains("ghp_supersecret"), "the token must not be readable on disk");
        // Properties.store escapes the colon, so the marker lands as `gcm1\:` on disk.
        assertTrue(onDisk.contains("gcm1"), "it should be a gcm1: token, however the colon is escaped");
        // The URL is not a secret and stays legible, so an operator can see where backups go.
        assertTrue(onDisk.contains("hk-backups.git"));
    }

    @Test
    void refusesATokenItCannotDecryptRatherThanHandingGarbageToGit(@TempDir Path dir) {
        Path file = dir.resolve("dest.properties");
        FileBackupDestinationStore.fromFile(file, cipher()).set(remote());

        // The vault key changed (or was lost). A push failing on "no destination" is diagnosable; one failing
        // on an authentication error because we passed ciphertext as a password is not.
        assertNull(FileBackupDestinationStore.fromFile(file, cipher()).get());
        assertNull(FileBackupDestinationStore.fromFile(file, null).get());
    }

    @Test
    void clearingReturnsTheAgentToLocalOnlyBackups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dest.properties");
        SecretCipher key = cipher();
        FileBackupDestinationStore store = FileBackupDestinationStore.fromFile(file, key);
        store.set(remote());

        store.set(null);

        assertNull(store.get());
        assertNull(FileBackupDestinationStore.fromFile(file, key).get());
        assertFalse(Files.readString(file).contains("hk-backups.git"), "the old destination must be gone");
    }

    @Test
    void storesTheTokenInTheClearOnlyWhenThereIsNoVaultKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dest.properties");

        FileBackupDestinationStore.fromFile(file, null).set(remote());

        // Documented fallback, and it is loud in the log. Asserted so the behaviour is deliberate, not a leak.
        assertTrue(Files.readString(file).contains("ghp_supersecret"));
        assertEquals("ghp_supersecret", FileBackupDestinationStore.fromFile(file, null).get().token());
    }
}
