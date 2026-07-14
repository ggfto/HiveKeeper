package io.hivekeeper.gateway.security;

import io.hivekeeper.gateway.enroll.CaFixtures;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the whole application with the {@code mtls} profile and checks it ends up with BOTH listeners.
 *
 * <p>Two listeners is the whole design, and neither may be dropped:
 * <ul>
 *   <li>the <b>TLS</b> one is where agents authenticate with a client certificate, which the gateway reads
 *       from the TLS connection itself — so it cannot sit behind a TLS-terminating proxy;</li>
 *   <li>the <b>plain HTTP</b> one is what the console's reverse proxy talks to over the internal network — so
 *       if enabling mTLS replaced it rather than adding to it, every deployment that hardened itself would
 *       lose its console.</li>
 * </ul>
 *
 * <p>This profile exists only in production, so nothing else in the suite loads it — and its first real run,
 * inside a container, failed instantly on a bean-name clash no unit test could have seen. A deployment is a
 * poor place to discover that the agent port does not come up: the whole fleet would be offline and the reason
 * would be twelve lines into a container log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mtls")
class AgentTlsConnectorTest {

    @DynamicPropertySource
    static void tlsStores(DynamicPropertyRegistry registry) throws Exception {
        // Real stores: Tomcat opens both while binding the connector, so a made-up path would fail this test
        // for the wrong reason. They are different kinds of file — the keystore holds the server's private key,
        // the truststore a trusted certificate — and passing a keystore where the truststore belongs fails with
        // "the trustAnchors parameter must be non-empty".
        char[] pw = "changeit".toCharArray();
        Path dir = Files.createTempDirectory("hk-agent-tls");
        Path keystore = CaFixtures.writeCaKeystore(dir.resolve("gateway.p12"), "gateway", pw);
        Path truststore = CaFixtures.writeTruststore(dir.resolve("truststore.p12"), "ca", pw);

        // Port 0: an ephemeral port, so the test never fights CI (or a developer's running stack) for 9443.
        registry.add("hivekeeper.agent-tls.port", () -> 0);
        registry.add("hivekeeper.agent-tls.keystore", keystore::toString);
        registry.add("hivekeeper.agent-tls.keystore-password", () -> "changeit");
        registry.add("hivekeeper.agent-tls.key-alias", () -> "gateway");
        registry.add("hivekeeper.agent-tls.truststore", truststore::toString);
        registry.add("hivekeeper.agent-tls.truststore-password", () -> "changeit");
    }

    @Autowired
    private ServletWebServerApplicationContext context;

    @Test
    void servesAgentsOverTlsAndTheConsoleOverPlainHttp() {
        Connector[] connectors = ((TomcatWebServer) context.getWebServer()).getTomcat().getService()
                .findConnectors();

        assertEquals(2, connectors.length, "expected a plain HTTP listener AND the agent TLS listener");
        assertTrue(Arrays.stream(connectors).anyMatch(c -> c.getSecure() && "https".equals(c.getScheme())),
                "the agents' TLS listener is missing");
        assertTrue(Arrays.stream(connectors).anyMatch(c -> !c.getSecure() && "http".equals(c.getScheme())),
                "the console's plain HTTP listener is missing — mTLS must ADD a port, not replace one");
    }
}
