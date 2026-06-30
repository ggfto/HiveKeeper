package io.hivekeeper.gateway.enroll;

import io.hivekeeper.gateway.tenant.InMemoryTenantStore;
import io.hivekeeper.gateway.tenant.TenantStore;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end of the certificate endpoint logic (no MockMvc): a valid one-time token mints a leaf + CA bundle
 * for the enrolled agentId; the token is then consumed (a reuse is refused); unknown tokens and a missing CA
 * are rejected with the right status.
 */
class EnrollmentCertificateControllerTest {

    private static final String TOKEN = "enroll-lab-agent";   // demo-seeded -> agent 'lab-agent', tenant 'acme'

    private final TenantStore tenants = new InMemoryTenantStore(true, false);

    private FileCertificateAuthority realCa() throws Exception {
        Path keystore = CaFixtures.writeCaKeystore(
                Files.createTempDirectory("ca").resolve("ca.p12"), "ca", "changeit".toCharArray());
        return new FileCertificateAuthority(keystore.toString(), "changeit", "ca");
    }

    private static String csrPem(String cn) throws Exception {
        PKCS10CertificationRequest csr = CaFixtures.newCsr(cn);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(csr);
        }
        return sw.toString();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    @Test
    void validTokenMintsACertForTheEnrolledAgent() throws Exception {
        var controller = new EnrollmentCertificateController(tenants, provider(realCa()));

        ResponseEntity<?> response = controller.issueCertificate(TOKEN, csrPem("ignored"));

        assertEquals(200, response.getStatusCode().value());
        List<X509Certificate> certs = parse((String) response.getBody());
        assertEquals(2, certs.size(), "leaf + CA chain");
        assertTrue(certs.get(0).getSubjectX500Principal().getName().contains("CN=lab-agent"),
                "leaf CN is the server-assigned agentId");
    }

    @Test
    void reusedTokenIsRefused() throws Exception {
        var controller = new EnrollmentCertificateController(tenants, provider(realCa()));

        assertEquals(200, controller.issueCertificate(TOKEN, csrPem("x")).getStatusCode().value());
        ResponseEntity<?> second = controller.issueCertificate(TOKEN, csrPem("x"));
        assertEquals(409, second.getStatusCode().value());
    }

    @Test
    void unknownTokenIsUnauthorized() throws Exception {
        var controller = new EnrollmentCertificateController(tenants, provider(realCa()));
        assertEquals(401, controller.issueCertificate("bogus", csrPem("x")).getStatusCode().value());
    }

    @Test
    void missingCaReportsNotImplemented() throws Exception {
        var controller = new EnrollmentCertificateController(tenants, provider(null));
        ResponseEntity<?> response = controller.issueCertificate(TOKEN, csrPem("x"));
        assertEquals(501, response.getStatusCode().value());
    }

    @Test
    void malformedCsrIsRejectedWithoutBurningTheToken() throws Exception {
        var controller = new EnrollmentCertificateController(tenants, provider(realCa()));

        assertEquals(400, controller.issueCertificate(TOKEN, "not a csr").getStatusCode().value());
        // The token must survive a bad request, so a corrected retry still succeeds.
        ResponseEntity<?> retry = controller.issueCertificate(TOKEN, csrPem("x"));
        assertEquals(200, retry.getStatusCode().value());
        assertInstanceOf(String.class, retry.getBody());
    }

    private static List<X509Certificate> parse(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
                .stream().map(c -> (X509Certificate) c).toList();
    }
}
