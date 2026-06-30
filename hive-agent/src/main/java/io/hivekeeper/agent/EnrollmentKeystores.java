package io.hivekeeper.agent;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared PKCS#10 / PKCS#12 helpers for the agent's certificate enrollment — used by both the first-time
 * {@link EnrollmentBootstrap} (token → CSR → cert) and the {@link EnrollmentRenewal} (mTLS re-issue before
 * expiry). Kept here so the CSR build, PEM-bundle parse, and keystore/truststore write logic lives in one place.
 */
final class EnrollmentKeystores {

    private EnrollmentKeystores() {
    }

    /** Build a PEM-encoded PKCS#10 CSR for {@code CN=<cn>} signed by {@code privateKey} over {@code publicKey}. */
    static String buildCsrPem(String cn, PublicKey publicKey, PrivateKey privateKey) throws Exception {
        JcaPKCS10CertificationRequestBuilder builder =
                new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + cn), publicKey);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);
        PKCS10CertificationRequest csr = builder.build(signer);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(csr);
        }
        return sw.toString();
    }

    /** Parse a PEM bundle (leaf first, then CA chain) into ordered X.509 certificates. */
    static List<X509Certificate> parseCertificates(String pemBundle) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(pemBundle.getBytes(StandardCharsets.UTF_8))) {
            for (Certificate c : cf.generateCertificates(in)) {
                certs.add((X509Certificate) c);
            }
        }
        return certs;
    }

    /** Write a PKCS12 keystore holding the agent's private key + its certificate chain (leaf first). */
    static void writeKeystore(Path path, char[] password, PrivateKey key, List<X509Certificate> chain)
            throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        keystore.load(null, null);
        keystore.setKeyEntry("agent", key, password, chain.toArray(new Certificate[0]));
        store(keystore, path, password);
    }

    /** Write a PKCS12 truststore holding the CA chain (the agent's trust anchors). */
    static void writeTruststore(Path path, char[] password, List<X509Certificate> caChain) throws Exception {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        truststore.load(null, null);
        for (int i = 0; i < caChain.size(); i++) {
            truststore.setCertificateEntry("ca-" + i, caChain.get(i));
        }
        store(truststore, path, password);
    }

    private static void store(KeyStore store, Path path, char[] password) throws Exception {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            store.store(out, password);
        }
    }
}
