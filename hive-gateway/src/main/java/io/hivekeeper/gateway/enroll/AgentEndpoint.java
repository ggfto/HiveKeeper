package io.hivekeeper.gateway.enroll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

/**
 * Where agents dial in — the hostname and port an on-prem agent must be pointed at.
 *
 * <p>The console used to make the operator type this by hand, because the gateway genuinely did not know it:
 * {@code HIVEKEEPER_AGENT_DOMAIN} is consumed by the PKI and tunnel init containers, never by the application.
 * But the fact is not actually missing — it is <b>baked into the gateway's own server certificate</b>. The PKI
 * generator mints that certificate with {@code san=dns:$HIVEKEEPER_AGENT_DOMAIN}, and an agent's TLS handshake
 * verifies the name it dialed against exactly that SAN. So the SAN <i>is</i> the agent-facing hostname, by
 * construction: any other value would fail the handshake, which makes it a better source than a second copy of
 * the setting that could drift out of step with the certificate.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code hivekeeper.agent.domain} ({@code HIVEKEEPER_AGENT_DOMAIN}) when set explicitly — an escape hatch
 *       for a deployment whose public name differs from the certificate's (a CNAME, a split-horizon DNS).</li>
 *   <li>the first dNSName SAN of the agent TLS listener's server certificate.</li>
 *   <li>unknown — {@link #host()} is {@code null} and the console falls back to asking. A gateway with no
 *       {@code mtls} profile has no agent listener at all, so this is the honest answer, not a failure.</li>
 * </ol>
 */
@Component
@Slf4j
public class AgentEndpoint {

    private final String host;
    private final int port;

    // The ONLY constructor, deliberately: a second one (a convenience (host, port) for tests) leaves Spring
    // unable to choose and it falls back to looking for a default constructor, which fails every context that
    // component-scans this package. Tests pass an explicit domain here instead — it short-circuits resolution.
    AgentEndpoint(@Value("${hivekeeper.agent.domain:}") String configuredDomain,
                  @Value("${hivekeeper.agent-tls.port:9443}") int port,
                  @Value("${hivekeeper.agent-tls.keystore:}") String keystore,
                  @Value("${hivekeeper.agent-tls.keystore-password:changeit}") String keystorePassword,
                  @Value("${hivekeeper.agent-tls.key-alias:gateway}") String keyAlias) {
        this.port = port;
        this.host = resolve(configuredDomain, keystore, keystorePassword, keyAlias);
        if (host == null) {
            log.info("agent endpoint hostname unknown (no hivekeeper.agent.domain, no agent TLS certificate) — "
                    + "the console will ask the operator for it");
        } else {
            log.info("agents dial {} (port {})", host, port);
        }
    }

    /** The hostname agents dial, or {@code null} when this gateway cannot tell. */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** The agent's {@code gateway.url}, or {@code null} when the hostname is unknown. */
    public String gatewayUrl() {
        return host == null ? null : "wss://" + host + ":" + port + "/agent";
    }

    /** The agent's {@code enrollment.url}, or {@code null} when the hostname is unknown. */
    public String enrollmentUrl() {
        return host == null ? null : "https://" + host + ":" + port;
    }

    private static String resolve(String configuredDomain, String keystore, String keystorePassword,
                                  String keyAlias) {
        if (configuredDomain != null && !configuredDomain.isBlank()) {
            return configuredDomain.trim();
        }
        if (keystore == null || keystore.isBlank()) {
            return null;
        }
        try {
            return sanHostname(serverCertificate(keystore, keystorePassword, keyAlias));
        } catch (Exception e) {
            // Never fatal: an unreadable keystore here only costs the console a prefilled field, and the TLS
            // connector would have failed far louder if it were genuinely broken.
            log.warn("could not read the agent hostname from the server certificate at {}: {}", keystore, e.toString());
            return null;
        }
    }

    private static X509Certificate serverCertificate(String keystore, String password, String alias)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        Path path = Path.of(keystore);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("keystore is not readable");
        }
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password == null ? null : password.toCharArray());
        }
        Certificate cert = ks.getCertificate(alias);
        if (cert == null) {
            throw new IllegalStateException("no certificate under alias '" + alias + "'");
        }
        if (!(cert instanceof X509Certificate x509)) {
            throw new IllegalStateException("certificate under alias '" + alias + "' is not X.509");
        }
        return x509;
    }

    /**
     * The first dNSName subject-alternative-name of a certificate, or {@code null} when it carries none.
     * SAN entry type {@code 2} is dNSName (RFC 5280); anything else — an IP, an email — is not a hostname an
     * agent can be pointed at, so it is skipped rather than guessed at.
     */
    static String sanHostname(X509Certificate cert) throws Exception {
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return null;
        }
        for (List<?> san : sans) {
            if (san.size() >= 2 && san.get(0) instanceof Integer type && type == 2
                    && san.get(1) instanceof String name && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }
}
