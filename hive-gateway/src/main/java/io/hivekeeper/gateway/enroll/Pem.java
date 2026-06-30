package io.hivekeeper.gateway.enroll;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.cert.X509Certificate;
import java.util.List;

/** PEM read/write helpers for the enrollment exchange (CSR in, leaf + CA chain out). */
final class Pem {

    private Pem() {
    }

    /** Parse a PKCS#10 CSR from its PEM encoding. */
    static PKCS10CertificationRequest parseCsr(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest csr) {
                return csr;
            }
            throw new IOException("body is not a PKCS#10 certification request");
        }
    }

    /**
     * Render the issued certificate followed by the CA chain as a single PEM bundle. The agent splits it on the
     * certificate boundaries: the first cert is its leaf, the rest are the CA chain (its truststore).
     */
    static String bundle(X509Certificate leaf, List<X509Certificate> caChain) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(leaf);
            for (X509Certificate ca : caChain) {
                writer.writeObject(ca);
            }
        }
        return sw.toString();
    }
}
