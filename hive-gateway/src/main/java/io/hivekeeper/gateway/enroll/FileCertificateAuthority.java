package io.hivekeeper.gateway.enroll;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * File-backed {@link CertificateAuthority}: loads a CA key + cert from a PKCS#12 keystore and signs leaf client
 * certificates with BouncyCastle. <b>Dev / self-hosted only</b> — the CA private key sits on the gateway's
 * disk. Production custody (KMS/HSM, intermediate CA, short-lived leaves, revocation) is deferred and lives
 * behind {@link CertificateAuthority}.
 *
 * <p>Active only when {@code hivekeeper.ca.keystore} is set (env {@code HIVEKEEPER_CA_KEYSTORE}); otherwise the
 * certificate endpoint reports that enrollment-by-CSR is not enabled. The dev keystore is the {@code ca.p12}
 * that {@code scripts/gen-dev-pki.ps1} already produces.
 */
@Component
@ConditionalOnProperty(name = "hivekeeper.ca.keystore")
@Slf4j
public class FileCertificateAuthority implements CertificateAuthority {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final X509Certificate caCert;
    private final PrivateKey caKey;

    public FileCertificateAuthority(
            @Value("${hivekeeper.ca.keystore}") String keystorePath,
            @Value("${hivekeeper.ca.keystore-password:changeit}") String keystorePassword,
            @Value("${hivekeeper.ca.alias:ca}") String alias) throws Exception {
        char[] pw = keystorePassword.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
            ks.load(in, pw);
        }
        this.caCert = (X509Certificate) ks.getCertificate(alias);
        Object key = ks.getKey(alias, pw);
        if (caCert == null || !(key instanceof PrivateKey privateKey)) {
            throw new IllegalStateException(
                    "CA keystore '" + keystorePath + "' has no key+cert under alias '" + alias + "'");
        }
        this.caKey = privateKey;
        log.info("file-backed CA loaded from {} (subject={})", keystorePath, caCert.getSubjectX500Principal());
    }

    @Override
    public X509Certificate sign(PKCS10CertificationRequest csr, String cn, Duration validity) throws Exception {
        PublicKey subjectKey = new JcaPKCS10CertificationRequest(csr).getPublicKey();
        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        X500Name subject = new X500Name("CN=" + cn);
        BigInteger serial = new BigInteger(159, RANDOM);   // 159-bit positive, collision-resistant
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));   // tolerate small clock skew
        Date notAfter = Date.from(now.plus(validity));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    @Override
    public List<X509Certificate> caChain() {
        return List.of(caCert);
    }
}
