package io.hivekeeper.gateway.enroll;

import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file CA signs a CSR into a leaf cert that (a) carries the server-assigned CN, (b) chains to the CA, and
 * (c) is marked for client authentication — the three properties the agent's mTLS identity depends on.
 */
class FileCertificateAuthorityTest {

    private static final char[] PW = "changeit".toCharArray();

    private FileCertificateAuthority ca() throws Exception {
        Path keystore = CaFixtures.writeCaKeystore(
                Files.createTempDirectory("ca").resolve("ca.p12"), "ca", PW);
        return new FileCertificateAuthority(keystore.toString(), "changeit", "ca");
    }

    @Test
    void signsACsrIntoALeafThatChainsToTheCa() throws Exception {
        FileCertificateAuthority ca = ca();
        PKCS10CertificationRequest csr = CaFixtures.newCsr("whatever-the-agent-asked-for");

        X509Certificate leaf = ca.sign(csr, "agent-7", Duration.ofDays(90));

        // CN is server-assigned (the enrollment's agentId), not whatever the CSR carried.
        assertTrue(leaf.getSubjectX500Principal().getName().contains("CN=agent-7"));
        // Chains to the CA: verifying with the CA public key must not throw.
        X509Certificate caCert = ca.caChain().get(0);
        assertDoesNotThrow(() -> leaf.verify(caCert.getPublicKey()));
        // Issued for client authentication.
        assertTrue(leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"), "EKU clientAuth");
    }

    @Test
    void overridesTheCsrSubjectWithTheAssignedIdentity() throws Exception {
        FileCertificateAuthority ca = ca();
        PKCS10CertificationRequest csr = CaFixtures.newCsr("attacker-chosen-name");

        X509Certificate leaf = ca.sign(csr, "real-agent", Duration.ofDays(90));

        assertTrue(leaf.getSubjectX500Principal().getName().contains("CN=real-agent"));
        assertEquals(1, ca.caChain().size());
    }
}
