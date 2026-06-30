package io.hivekeeper.agent;

import io.hivekeeper.agent.radius.RadiusProvisioner;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.PpskUserRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link FilePpskUserStore}: store/reload roundtrip with policy fields, at-rest encryption, revoke, and
 *  that the RADIUS provisioner is re-run from the full set after every mutation. */
class FilePpskUserStoreTest {

    private static final String VAULT_KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";

    private static PpskUserRecord alice(String psk) {
        return new PpskUserRecord("Corp", "staff", "alice", psk, 99, 30, "biz-hours",
                List.of("aa:bb:cc:dd:ee:ff"), "active");
    }

    @Test
    void storesAndReloadsAllPolicyFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ppsk.properties");
        FilePpskUserStore store = FilePpskUserStore.fromFile(file, null, null);
        store.put(alice("psk-aaa"));

        FilePpskUserStore reloaded = FilePpskUserStore.fromFile(file, null, null);
        List<PpskUserRecord> list = reloaded.list();
        assertEquals(1, list.size());
        PpskUserRecord r = list.get(0);
        assertEquals("Corp", r.securityObject());
        assertEquals("staff", r.userGroup());
        assertEquals("alice", r.username());
        assertEquals("psk-aaa", r.psk());
        assertEquals(99, r.userProfileAttr());
        assertEquals(30, r.vlanId());
        assertEquals("biz-hours", r.scheduleName());
        assertEquals(List.of("aa:bb:cc:dd:ee:ff"), r.macBindings());
    }

    @Test
    void encryptsThePskAtRestButResolvesInTheClear(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ppsk.properties");
        SecretCipher cipher = SecretCipher.fromBase64(VAULT_KEY);
        FilePpskUserStore store = FilePpskUserStore.fromFile(file, cipher, null);
        store.put(alice("top-secret-psk"));

        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains("top-secret-psk"), "the PSK must not be plaintext on disk");
        // Properties.store() backslash-escapes ':' in values, so the on-disk token reads "gcm1\:...".
        assertTrue(onDisk.contains("gcm1"), "the PSK must be an encrypted token");

        FilePpskUserStore reloaded = FilePpskUserStore.fromFile(file, cipher, null);
        assertEquals("top-secret-psk", reloaded.list().get(0).psk());
    }

    @Test
    void rotateOverwritesAndRevokeRemoves(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ppsk.properties");
        FilePpskUserStore store = FilePpskUserStore.fromFile(file, null, null);
        store.put(alice("psk-1"));
        store.put(alice("psk-2"));   // same key (Corp/alice) → rotate
        assertEquals(1, store.list().size());
        assertEquals("psk-2", store.list().get(0).psk());

        assertTrue(store.remove("Corp", "alice"));
        assertFalse(store.remove("Corp", "alice"));   // already gone
        assertTrue(store.list().isEmpty());
        // Reload confirms the removal persisted.
        assertTrue(FilePpskUserStore.fromFile(file, null, null).list().isEmpty());
    }

    @Test
    void anEncryptedEntryIsSkippedWhenNoKeyIsConfigured(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ppsk.properties");
        FilePpskUserStore store = FilePpskUserStore.fromFile(file, SecretCipher.fromBase64(VAULT_KEY), null);
        store.put(alice("secret"));

        // Reload without the key: the encrypted PSK cannot be read, so the entry is skipped (never ciphertext).
        FilePpskUserStore reloaded = FilePpskUserStore.fromFile(file, null, null);
        assertTrue(reloaded.list().isEmpty());
    }

    @Test
    void reprovisionsTheRadiusServerFromTheFullSetOnEveryMutation(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ppsk.properties");
        List<List<String>> provisionedUsernames = new ArrayList<>();
        RadiusProvisioner spy = users ->
                provisionedUsernames.add(users.stream().map(PpskUserRecord::username).sorted().toList());

        FilePpskUserStore store = FilePpskUserStore.fromFile(file, null, spy);   // 1: initial load (empty)
        store.put(alice("p1"));                                                  // 2: after create
        store.put(new PpskUserRecord("Corp", "staff", "bob", "p2", null, null, null, List.of(), "active")); // 3
        store.remove("Corp", "alice");                                           // 4: after revoke

        assertEquals(List.of(), provisionedUsernames.get(0));
        assertEquals(List.of("alice"), provisionedUsernames.get(1));
        assertEquals(List.of("alice", "bob"), provisionedUsernames.get(2));
        assertEquals(List.of("bob"), provisionedUsernames.get(3));
    }

    @Test
    void aMissingFileStartsEmpty(@TempDir Path dir) throws IOException {
        assertNull(FilePpskUserStore.fromFile(dir.resolve("nope.properties"), null, null).list().stream()
                .findFirst().orElse(null));
    }
}
