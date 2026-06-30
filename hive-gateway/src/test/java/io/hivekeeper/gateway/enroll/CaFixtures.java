package io.hivekeeper.gateway.enroll;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Test PKI helpers: write a CA keystore the file CA can load, and build CSRs to sign. */
final class CaFixtures {

    private CaFixtures() {
    }

    /** Generate a self-signed CA and write it to a PKCS12 keystore; return the keystore path. */
    static Path writeCaKeystore(Path file, String alias, char[] password) throws Exception {
        KeyPair caKeys = rsa();
        X509Certificate caCert = selfSignedCa(caKeys);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, caKeys.getPrivate(), password, new Certificate[]{caCert});
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, password);
        }
        return file;
    }

    /** Build a PKCS#10 CSR for the given subject CN with a fresh keypair. */
    static PKCS10CertificationRequest newCsr(String cn) throws Exception {
        KeyPair keys = rsa();
        JcaPKCS10CertificationRequestBuilder builder =
                new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + cn), keys.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        return builder.build(signer);
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static X509Certificate selfSignedCa(KeyPair caKeys) throws Exception {
        X500Name name = new X500Name("CN=HiveKeeper Test CA,O=HiveKeeper");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(825))), name, caKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
