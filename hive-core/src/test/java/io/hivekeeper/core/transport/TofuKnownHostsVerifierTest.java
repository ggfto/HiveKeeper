package io.hivekeeper.core.transport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the TOFU verifier directly via {@link TofuKnownHostsVerifier#verify} — no live SSH server. The
 * contract: first sight of a host records + accepts its key; the same key later accepts; a different key for a
 * known host is refused; and recorded keys persist across reloads of the file.
 */
class TofuKnownHostsVerifierTest {

    private static final String HOST = "192.168.1.101";
    private static final int PORT = 22;

    private static PublicKey rsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        return pair.getPublic();
    }

    @Test
    void firstUse_records_and_accepts() throws Exception {
        Path dir = Files.createTempDirectory("kh");
        Path khFile = dir.resolve("known_hosts");
        PublicKey key = rsaKey();

        TofuKnownHostsVerifier verifier = new TofuKnownHostsVerifier(khFile.toFile());
        assertTrue(verifier.verify(HOST, PORT, key), "unknown host should be trusted on first use");
        assertTrue(Files.exists(khFile), "first trust should create the known_hosts file");
        assertTrue(Files.readString(khFile).contains(HOST), "the host should be recorded");
    }

    @Test
    void sameKey_accepts_and_persists_across_reload() throws Exception {
        Path dir = Files.createTempDirectory("kh");
        Path khFile = dir.resolve("known_hosts");
        PublicKey key = rsaKey();

        new TofuKnownHostsVerifier(khFile.toFile()).verify(HOST, PORT, key); // record

        // A fresh verifier reloads the file: the same key must be accepted without re-prompting.
        TofuKnownHostsVerifier reloaded = new TofuKnownHostsVerifier(khFile.toFile());
        assertTrue(reloaded.verify(HOST, PORT, key), "a matching recorded key should be accepted");
    }

    @Test
    void changedKey_is_rejected() throws Exception {
        Path dir = Files.createTempDirectory("kh");
        Path khFile = dir.resolve("known_hosts");
        PublicKey first = rsaKey();
        PublicKey different = rsaKey();

        new TofuKnownHostsVerifier(khFile.toFile()).verify(HOST, PORT, first); // record the first key

        TofuKnownHostsVerifier reloaded = new TofuKnownHostsVerifier(khFile.toFile());
        assertFalse(reloaded.verify(HOST, PORT, different),
                "a different key for a known host must be refused (possible MITM)");
    }
}
