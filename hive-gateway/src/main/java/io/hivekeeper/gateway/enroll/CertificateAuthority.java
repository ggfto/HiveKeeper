package io.hivekeeper.gateway.enroll;

import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

/**
 * Signs agent client certificates during enrollment. Kept behind this interface so the dev/self-hosted
 * file-backed signer ({@link FileCertificateAuthority}) can later be swapped for a KMS/HSM-backed intermediate
 * CA without touching the endpoint — only the custody of the CA key changes.
 */
public interface CertificateAuthority {

    /**
     * Issue a leaf client certificate for the CSR's public key, with subject {@code CN=<cn>} (the gateway
     * assigns the identity; the CSR's own subject is ignored) and EKU {@code clientAuth}. The CA only trusts
     * the CSR's public key.
     */
    X509Certificate sign(PKCS10CertificationRequest csr, String cn, Duration validity) throws Exception;

    /** The CA certificate chain the agent must trust (its truststore); for the file CA, the single CA cert. */
    List<X509Certificate> caChain();

    /**
     * The CA chain as a PEM bundle — the exact bytes that go in the agent's {@code ca.pem}. Public, not a secret,
     * so the console can show it at enrollment time instead of the operator digging it out of a container log.
     */
    default String caPem() {
        try {
            return Pem.certificates(caChain());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to render the CA chain as PEM", e);
        }
    }
}
