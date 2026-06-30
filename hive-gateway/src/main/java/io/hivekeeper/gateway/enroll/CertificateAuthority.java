package io.hivekeeper.gateway.enroll;

import org.bouncycastle.pkcs.PKCS10CertificationRequest;

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
}
