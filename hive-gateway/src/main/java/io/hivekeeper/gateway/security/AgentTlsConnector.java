package io.hivekeeper.gateway.security;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gives the gateway a SECOND listener: a TLS port where agents present a client certificate, alongside the
 * plain HTTP port the console's reverse proxy talks to.
 *
 * <h2>Why two, and not one behind a proxy</h2>
 * An agent authenticates with its mTLS client certificate, and the gateway reads that certificate from the
 * servlet's {@code jakarta.servlet.request.X509Certificate} attribute — the TLS connection itself — never from
 * a header. That is the right call (a header can be forged by anything that can reach the port), but it has a
 * consequence: <b>a TLS-terminating reverse proxy in front of the agent's port breaks agent authentication</b>.
 * The proxy would terminate the handshake, the certificate would never reach Tomcat, and every agent handshake
 * and certificate renewal would fail — closed, silently, until the fleet's certs expired.
 *
 * <p>So the agent port is published as-is and the TLS handshake runs all the way to the gateway. Browsers and
 * the console keep going through the reverse proxy, which terminates TLS for them and forwards plain HTTP to
 * the main port on the internal network.
 *
 * <p>{@code client-auth=want}, not {@code need}: the handshake succeeds without a certificate, and it is
 * {@code AgentAuthInterceptor} that requires and validates one on the {@code /agent} WebSocket and on
 * certificate renewal. That way an unenrolled agent gets a clear application-level rejection instead of an
 * opaque TLS failure — and enrollment (which by definition has no certificate yet) can still be reached.
 */
@Configuration
@Profile("mtls")
public class AgentTlsConnector {

    // Not named agentTlsConnector: that is this @Configuration class's own bean name, and the clash stops the
    // context from starting.
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> agentTlsCustomizer(
            @Value("${hivekeeper.agent-tls.port:9443}") int port,
            @Value("${hivekeeper.agent-tls.keystore}") String keystore,
            @Value("${hivekeeper.agent-tls.keystore-password:changeit}") String keystorePassword,
            @Value("${hivekeeper.agent-tls.key-alias:gateway}") String keyAlias,
            @Value("${hivekeeper.agent-tls.truststore}") String truststore,
            @Value("${hivekeeper.agent-tls.truststore-password:changeit}") String truststorePassword) {

        return factory -> factory.addAdditionalTomcatConnectors(
                connector(port, keystore, keystorePassword, keyAlias, truststore, truststorePassword));
    }

    private static Connector connector(int port, String keystore, String keystorePassword, String keyAlias,
                                       String truststore, String truststorePassword) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);

        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);

        SSLHostConfig ssl = new SSLHostConfig();
        // "want": present a certificate if you have one. AgentAuthInterceptor is what insists on a valid,
        // enrolled one — so a rejected agent gets an application error it can read, not a TLS reset.
        ssl.setCertificateVerification("want");

        SSLHostConfigCertificate certificate =
                new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateKeystoreFile(keystore);
        certificate.setCertificateKeystorePassword(keystorePassword);
        certificate.setCertificateKeystoreType("PKCS12");
        certificate.setCertificateKeyAlias(keyAlias);
        ssl.addCertificate(certificate);

        // The CA that signed the agents' certificates: this is what makes a client cert verifiable at all.
        ssl.setTruststoreFile(truststore);
        ssl.setTruststorePassword(truststorePassword);
        ssl.setTruststoreType("PKCS12");

        protocol.addSslHostConfig(ssl);
        return connector;
    }
}
