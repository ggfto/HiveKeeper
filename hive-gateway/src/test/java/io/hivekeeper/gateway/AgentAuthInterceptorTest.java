package io.hivekeeper.gateway;

import io.hivekeeper.gateway.tenant.InMemoryTenantStore;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handshake auth seam must refuse a REVOKED agent even though its certificate is otherwise valid and
 * enrolled. Uses the demo-seeded {@code lab-agent} (tenant {@code acme}).
 */
class AgentAuthInterceptorTest {

    private final InMemoryTenantStore tenants = new InMemoryTenantStore(true, false);
    private final AgentAuthInterceptor interceptor = new AgentAuthInterceptor(tenants);

    @Test
    void anEnrolledNonRevokedCertAuthenticatesAndStampsIdentity() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = handshake(selfSigned("lab-agent"), attributes);

        assertTrue(ok);
        assertEquals("lab-agent", attributes.get(AgentAuthInterceptor.ATTR_AGENT_ID));
        assertEquals("acme", attributes.get(AgentAuthInterceptor.ATTR_TENANT_ID));
    }

    @Test
    void aRevokedCertIsRejected() throws Exception {
        tenants.revokeAgent("acme", "lab-agent", "compromised");

        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        boolean ok = interceptor.beforeHandshake(
                requestWithCert(selfSigned("lab-agent")), new ServletServerHttpResponse(rawResponse), null, attributes);

        assertFalse(ok);
        assertEquals(403, rawResponse.getStatus());
        assertFalse(attributes.containsKey(AgentAuthInterceptor.ATTR_AGENT_ID));
    }

    private boolean handshake(X509Certificate clientCert, Map<String, Object> attributes) {
        return interceptor.beforeHandshake(requestWithCert(clientCert),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes);
    }

    private static ServletServerHttpRequest requestWithCert(X509Certificate cert) {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[]{cert});
        return new ServletServerHttpRequest(raw);
    }

    private static X509Certificate selfSigned(String cn) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keys = gen.generateKeyPair();
        X500Name name = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1), Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(1))), name, keys.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
