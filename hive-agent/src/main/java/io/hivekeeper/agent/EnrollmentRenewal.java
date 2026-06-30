package io.hivekeeper.agent;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Auto-renewal of the agent's mTLS client certificate before it expires (slice 2 of automated enrollment).
 * Unlike the one-time {@link EnrollmentBootstrap}, renewal is authenticated by the agent's CURRENT certificate
 * (mTLS) — no token — so it is repeatable. The agent KEEPS its keypair (renewal is not a rekey): it posts a CSR
 * over its existing public key and writes a new keystore with a fresh leaf and the same private key. Keeping the
 * key means the gateway's cached public key (used to seal secrets to this agent) is unchanged, so nothing else
 * has to be refreshed when the cert rolls over.
 */
@Slf4j
final class EnrollmentRenewal {

    private EnrollmentRenewal() {
    }

    /** Posts a renewal CSR and returns the PEM bundle (leaf + CA chain). Separated so tests drive it without a
     *  live mTLS gateway. */
    @FunctionalInterface
    interface CsrExchange {
        String send(String csrPem) throws Exception;
    }

    /**
     * True when the leaf has entered its renewal window — within {@code window} of {@code notAfter}, or already
     * expired. Pure, so the scheduling decision is unit-testable at the boundaries.
     */
    static boolean dueForRenewal(X509Certificate leaf, Instant now, Duration window) {
        Instant renewAt = leaf.getNotAfter().toInstant().minus(window);
        return !now.isBefore(renewAt);
    }

    /** Renew over the live mTLS endpoint (production path). */
    static X509Certificate renew(AgentConfig config) throws Exception {
        return renew(config, csrPem -> requestRenewal(config, csrPem));
    }

    /**
     * Renew the agent's certificate: reuse its keypair, run {@code exchange} to obtain a fresh leaf + CA bundle,
     * and rewrite the keystore (same key, new chain) + truststore. Returns the new leaf certificate.
     */
    static X509Certificate renew(AgentConfig config, CsrExchange exchange) throws Exception {
        char[] ksPw = config.tlsKeystorePassword().toCharArray();
        PrivateKey key = TlsSupport.privateKey(config.tlsKeystore(), ksPw);
        PublicKey publicKey = TlsSupport.clientCertificate(config.tlsKeystore(), ksPw).getPublicKey();

        String csrPem = EnrollmentKeystores.buildCsrPem(config.agentId(), publicKey, key);
        String bundle = exchange.send(csrPem);
        List<X509Certificate> chain = EnrollmentKeystores.parseCertificates(bundle);
        if (chain.isEmpty()) {
            throw new IllegalStateException("renewal response contained no certificates");
        }
        List<X509Certificate> caChain = chain.subList(1, chain.size());

        EnrollmentKeystores.writeKeystore(Path.of(config.tlsKeystore()), ksPw, key, chain);
        EnrollmentKeystores.writeTruststore(Path.of(config.tlsTruststore()),
                config.tlsTruststorePassword().toCharArray(), caChain);
        X509Certificate newLeaf = chain.get(0);
        log.info("certificate renewed for agent '{}' — valid until {}", config.agentId(), newLeaf.getNotAfter());
        return newLeaf;
    }

    private static String requestRenewal(AgentConfig config, String csrPem) throws Exception {
        String base = config.enrollmentUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/api/enrollments/certificate/renew");
        // Present the agent's CURRENT mTLS identity (client cert) and trust the gateway via its truststore.
        SSLContext ssl = TlsSupport.fromKeystores(config.tlsKeystore(), config.tlsKeystorePassword().toCharArray(),
                config.tlsTruststore(), config.tlsTruststorePassword().toCharArray());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-pem-file")
                .POST(HttpRequest.BodyPublishers.ofString(csrPem, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).sslContext(ssl).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("renewal request to " + uri + " failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        return response.body();
    }
}
