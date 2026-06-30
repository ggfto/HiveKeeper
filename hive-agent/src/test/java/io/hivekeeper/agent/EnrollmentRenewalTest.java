package io.hivekeeper.agent;

import io.hivekeeper.core.transport.HostKeyPolicy;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renewal decision boundaries, plus a loopback that drives {@link EnrollmentRenewal#renew} with a stub CSR
 * exchange (a CA that signs the posted CSR): the rewritten keystore must KEEP the agent's keypair (renewal is
 * not a rekey) and carry the freshly issued leaf, with the CA in the truststore.
 */
class EnrollmentRenewalTest {

    @Test
    void notDueWhenFarFromExpiry() throws Exception {
        X509Certificate leaf = selfSigned("lab-agent", rsa(), Duration.ofDays(90));
        assertFalse(EnrollmentRenewal.dueForRenewal(leaf, Instant.now(), Duration.ofDays(30)));
    }

    @Test
    void dueOnceInsideTheWindow() throws Exception {
        X509Certificate leaf = selfSigned("lab-agent", rsa(), Duration.ofDays(10));
        assertTrue(EnrollmentRenewal.dueForRenewal(leaf, Instant.now(), Duration.ofDays(30)));
    }

    @Test
    void dueWhenAlreadyExpired() throws Exception {
        X509Certificate leaf = selfSigned("lab-agent", rsa(), Duration.ofDays(-1));
        assertTrue(EnrollmentRenewal.dueForRenewal(leaf, Instant.now(), Duration.ofDays(30)));
    }

    @Test
    void renewKeepsTheKeypairAndWritesTheNewLeafAndCa() throws Exception {
        Path dir = Files.createTempDirectory("renew");
        Path keystore = dir.resolve("agent.p12");
        Path truststore = dir.resolve("truststore.p12");
        char[] pw = "changeit".toCharArray();

        // Existing identity: the agent's keypair + a soon-to-expire self-signed leaf.
        KeyPair agentKeys = rsa();
        X509Certificate oldLeaf = selfSigned("lab-agent", agentKeys, Duration.ofDays(5));
        EnrollmentKeystores.writeKeystore(keystore, pw, agentKeys.getPrivate(), List.of(oldLeaf));

        // The "gateway": a CA that signs the CSR (over the agent's existing public key) and returns leaf + CA.
        KeyPair ca = rsa();
        X509Certificate caCert = selfSignedCa(ca);
        AgentConfig config = config(keystore, truststore);

        X509Certificate newLeaf = EnrollmentRenewal.renew(config, csrPem -> signToBundle(csrPem, ca, caCert));

        // The keypair is unchanged (renewal is not a rekey): the keystore's private key is the original.
        PrivateKey rewritten = TlsSupport.privateKey(keystore.toString(), pw);
        assertArrayEquals(agentKeys.getPrivate().getEncoded(), rewritten.getEncoded(), "keypair reused");
        assertArrayEquals(agentKeys.getPublic().getEncoded(), newLeaf.getPublicKey().getEncoded(),
                "new leaf carries the same public key");

        // The new leaf is the CA-issued one (its issuer is the CA, not self), and it is what we wrote.
        assertTrue(newLeaf.getIssuerX500Principal().getName().contains("HiveKeeper Test CA"));
        X509Certificate stored = TlsSupport.clientCertificate(keystore.toString(), pw);
        assertArrayEquals(newLeaf.getEncoded(), stored.getEncoded(), "keystore holds the renewed leaf");

        // The truststore (PKCS12) holds the CA the gateway returned.
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststore)) {
            trust.load(in, pw);
        }
        X509Certificate trusted = (X509Certificate) trust.getCertificate("ca-0");
        assertArrayEquals(caCert.getEncoded(), trusted.getEncoded(), "the CA is in the truststore");
    }

    // --- helpers ---------------------------------------------------------------

    private static AgentConfig config(Path keystore, Path truststore) {
        return new AgentConfig(
                java.net.URI.create("wss://127.0.0.1:9443/agent"), "lab-agent", "admin", "",
                null, null, null, null, "hivekeeper-backups",
                keystore.toString(), "changeit", truststore.toString(), "changeit",
                HostKeyPolicy.TOFU, "hivekeeper-known_hosts",
                null, "https://127.0.0.1:9443", null, 30, 12);
    }

    private static String signToBundle(String csrPem, KeyPair ca, X509Certificate caCert) throws Exception {
        PKCS10CertificationRequest csr;
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            csr = (PKCS10CertificationRequest) parser.readObject();
        }
        PublicKey subjectKey = new JcaPKCS10CertificationRequest(csr).getPublicKey();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded()), BigInteger.valueOf(2),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(90))),
                new X500Name("CN=lab-agent"), subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(holder);

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(leaf);
            w.writeObject(caCert);
        }
        return sw.toString();
    }

    private static X509Certificate selfSigned(String cn, KeyPair keys, Duration validFor) throws Exception {
        X500Name name = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1), Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(validFor)), name, keys.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate selfSignedCa(KeyPair ca) throws Exception {
        X500Name name = new X500Name("CN=HiveKeeper Test CA,O=HiveKeeper");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(825))), name, ca.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }
}
