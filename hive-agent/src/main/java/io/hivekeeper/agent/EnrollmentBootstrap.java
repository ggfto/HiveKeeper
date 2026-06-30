package io.hivekeeper.agent;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Automated certificate enrollment for a fresh agent: given a one-time token and the gateway URL, generate a
 * keypair locally, post a CSR, and write the issued client keystore + CA truststore — so the agent can connect
 * over mTLS without a pre-provisioned cert.
 *
 * <p>The exchange is intentionally dependency-light: the CSR is sent as a PEM body and the response is a PEM
 * bundle whose first certificate is the agent's leaf and the rest are the CA chain (its truststore). The
 * gateway assigns the identity (cert {@code CN = agentId}), so the CSR subject here is only a hint.
 *
 * <p>Slice 1 scope: bootstrap only. Auto-renewal and revocation are deferred.
 */
@Slf4j
final class EnrollmentBootstrap {

    private EnrollmentBootstrap() {
    }

    /**
     * Run the bootstrap: write a PKCS12 keystore (key + issued cert chain) at {@code tlsKeystore} and a PKCS12
     * truststore (CA chain) at {@code tlsTruststore}. Caller guarantees the keystore does not already exist and
     * that {@link AgentConfig#enrollmentConfigured()} is true.
     */
    static void run(AgentConfig config) throws Exception {
        log.info("no keystore at {} — bootstrapping a certificate via enrollment token", config.tlsKeystore());

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        String csrPem = EnrollmentKeystores.buildCsrPem(config.agentId(), keyPair.getPublic(), keyPair.getPrivate());
        String bundle = requestCertificate(config, csrPem);
        List<X509Certificate> chain = EnrollmentKeystores.parseCertificates(bundle);
        if (chain.isEmpty()) {
            throw new IllegalStateException("enrollment response contained no certificates");
        }
        List<X509Certificate> caChain = chain.subList(1, chain.size());
        if (caChain.isEmpty()) {
            log.warn("enrollment response carried only a leaf certificate; truststore will be empty");
        }

        EnrollmentKeystores.writeKeystore(Path.of(config.tlsKeystore()),
                config.tlsKeystorePassword().toCharArray(), keyPair.getPrivate(), chain);
        EnrollmentKeystores.writeTruststore(Path.of(config.tlsTruststore()),
                config.tlsTruststorePassword().toCharArray(), caChain);
        log.info("enrollment complete: keystore -> {}, truststore -> {} ({} CA cert(s))",
                config.tlsKeystore(), config.tlsTruststore(), caChain.size());
    }

    private static String requestCertificate(AgentConfig config, String csrPem) throws Exception {
        String base = config.enrollmentUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/api/enrollments/" + config.enrollmentToken() + "/certificate");

        HttpClient.Builder clientBuilder = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10));
        if (config.enrollmentCaCert() != null && !config.enrollmentCaCert().isBlank()) {
            clientBuilder.sslContext(trustContext(config.enrollmentCaCert()));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-pem-file")
                .POST(HttpRequest.BodyPublishers.ofString(csrPem, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = clientBuilder.build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "enrollment request to " + uri + " failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    private static SSLContext trustContext(String caCertPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(Path.of(caCertPath))) {
            int i = 0;
            for (Certificate c : cf.generateCertificates(in)) {
                trust.setCertificateEntry("bootstrap-ca-" + (i++), c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }
}
