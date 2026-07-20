package io.hivekeeper.agent;

import com.sun.net.httpserver.HttpServer;
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

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback for the agent bootstrap: a stub HTTP endpoint signs the posted CSR and returns a leaf + CA PEM
 * bundle; the bootstrap must then write a PKCS12 keystore loadable by {@link TlsSupport} and a truststore
 * holding the CA. Mirrors the WebSocketLoopbackTest style — no real gateway.
 */
class EnrollmentBootstrapTest {

    @Test
    void bootstrapWritesAnMtlsKeystoreAndTruststore() throws Exception {
        KeyPair ca = rsa();
        java.security.cert.X509Certificate caCert = selfSignedCa(ca);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/enrollments/tok/certificate", exchange -> {
            try {
                String csrPem = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body = signToBundle(csrPem, ca, caCert).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/x-pem-file");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        Path dir = Files.createTempDirectory("enroll");
        Path keystore = dir.resolve("agent.p12");
        Path truststore = dir.resolve("truststore.p12");
        AgentConfig config = config(keystore, truststore, "http://127.0.0.1:" + port);

        try {
            EnrollmentBootstrap.run(config);
        } finally {
            server.stop(0);
        }

        assertTrue(Files.exists(keystore), "keystore written");
        assertTrue(Files.exists(truststore), "truststore written");

        // The written keystore must carry the agent's private key (the same TlsSupport mTLS path uses).
        PrivateKey key = TlsSupport.privateKey(keystore.toString(), "changeit".toCharArray());
        assertNotNull(key);

        // The truststore must hold the CA the gateway returned.
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststore)) {
            trust.load(in, "changeit".toCharArray());
        }
        assertEquals(1, trust.size(), "one CA cert in the truststore");
    }

    private static AgentConfig config(Path keystore, Path truststore, String enrollUrl) {
        return new AgentConfig(
                java.net.URI.create("ws://127.0.0.1:8090/agent"), "lab-agent", "admin", "",
                null, null, null, null, "hivekeeper-backups", "hivekeeper-backup-destination.properties",
                keystore.toString(), "changeit", truststore.toString(), "changeit",
                HostKeyPolicy.TOFU, "hivekeeper-known_hosts",
                "tok", enrollUrl, null, 30, 12, "hivekeeper-agent.health");
    }

    // --- stub gateway CA -------------------------------------------------------

    private static String signToBundle(String csrPem, KeyPair ca, java.security.cert.X509Certificate caCert)
            throws Exception {
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
        java.security.cert.X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(holder);

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(leaf);
            w.writeObject(caCert);
        }
        return sw.toString();
    }

    private static java.security.cert.X509Certificate selfSignedCa(KeyPair ca) throws Exception {
        X500Name name = new X500Name("CN=HiveKeeper Test CA,O=HiveKeeper");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(825))), name, ca.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }
}
