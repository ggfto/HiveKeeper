package io.hivekeeper.gateway.enroll;

import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;

/**
 * Automated agent enrollment, second half: exchange a one-time enrollment token for a signed client
 * certificate. The agent generates its own keypair and posts a PKCS#10 CSR; the gateway's CA signs a leaf cert
 * with {@code CN = the enrollment's agentId} (server-assigned identity — the CSR subject is ignored) and
 * returns the leaf + CA chain as a PEM bundle, which the agent writes to its keystore/truststore for mTLS.
 *
 * <p><b>Auth is the token itself</b> — no user, no mTLS (the agent has no cert yet), so this endpoint stays
 * outside the user-authenticated controllers. The token is one-time: it is consumed atomically here, so it can
 * mint exactly one certificate.
 */
@RestController
@Slf4j
public class EnrollmentCertificateController {

    private static final Duration LEAF_VALIDITY = Duration.ofDays(90);
    private static final MediaType PEM = MediaType.valueOf("application/x-pem-file");

    private final TenantStore tenants;
    private final ObjectProvider<CertificateAuthority> ca;

    EnrollmentCertificateController(TenantStore tenants, ObjectProvider<CertificateAuthority> ca) {
        this.tenants = tenants;
        this.ca = ca;
    }

    public record ApiError(String error, String detail) {
    }

    @PostMapping(value = "/api/enrollments/{token}/certificate",
            consumes = {"application/x-pem-file", MediaType.TEXT_PLAIN_VALUE},
            produces = "application/x-pem-file")
    public ResponseEntity<?> issueCertificate(@PathVariable String token, @RequestBody String csrPem) {
        CertificateAuthority authority = ca.getIfAvailable();
        if (authority == null) {
            return ResponseEntity.status(501).contentType(MediaType.APPLICATION_JSON).body(new ApiError(
                    "not_supported", "certificate enrollment is not enabled (no CA configured — set HIVEKEEPER_CA_KEYSTORE)"));
        }

        Optional<AgentEnrollment> enrollment = tenants.enrollmentByToken(token);
        if (enrollment.isEmpty()) {
            log.warn("certificate enrollment rejected: unknown enrollment token");
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("invalid_token", "unknown or invalid enrollment token"));
        }

        String agentId = enrollment.get().agentId();
        String bundle;
        try {
            PKCS10CertificationRequest csr = Pem.parseCsr(csrPem);
            // Identity is server-assigned: sign CN = the enrolled agentId, ignoring the CSR's own subject, so
            // the existing cert-CN -> agentId mapping holds and an agent cannot choose its own identity.
            X509Certificate leaf = authority.sign(csr, agentId, LEAF_VALIDITY);
            bundle = Pem.bundle(leaf, authority.caChain());
        } catch (Exception e) {
            log.warn("certificate enrollment failed for agent '{}': {}", agentId, e.toString());
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("bad_csr", "could not sign the certificate request: " + e.getMessage()));
        }

        // Burn the one-time token AFTER signing succeeds. Atomic: only the first caller wins, so a reused or
        // raced token is refused here and never receives the (already-signed-but-undelivered) certificate.
        if (!tenants.markEnrollmentConsumed(token)) {
            log.warn("certificate enrollment rejected: token for agent '{}' was already consumed", agentId);
            return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("token_consumed", "this enrollment token has already been used"));
        }

        log.info("issued client certificate for agent '{}' (tenant '{}')", agentId, enrollment.get().tenantId());
        return ResponseEntity.ok().contentType(PEM).body(bundle);
    }

    /**
     * Renew an agent's client certificate before it expires. Unlike the bootstrap above, this is authenticated
     * by the agent's CURRENT mTLS client certificate (no token — renewal is repeatable), exactly as the
     * WebSocket handshake authenticates: the identity is the cert's CN, resolved to an enrollment server-side.
     * The agent keeps its keypair and posts a fresh CSR; the CA issues a new leaf with the same
     * {@code CN = agentId} and a fresh validity window. A revoked agent is refused (its handshake is too).
     *
     * <p>{@code server.ssl.client-auth=want} makes the client cert available here on the servlet request when
     * the agent presents one; a request without a verified client cert is rejected.
     */
    @PostMapping(value = "/api/enrollments/certificate/renew",
            consumes = {"application/x-pem-file", MediaType.TEXT_PLAIN_VALUE},
            produces = "application/x-pem-file")
    public ResponseEntity<?> renewCertificate(@RequestBody String csrPem, HttpServletRequest request) {
        CertificateAuthority authority = ca.getIfAvailable();
        if (authority == null) {
            return ResponseEntity.status(501).contentType(MediaType.APPLICATION_JSON).body(new ApiError(
                    "not_supported", "certificate enrollment is not enabled (no CA configured — set HIVEKEEPER_CA_KEYSTORE)"));
        }

        String agentId = clientCertCommonName(request);
        if (agentId == null) {
            log.warn("certificate renewal rejected: no verified client certificate on the request");
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("no_client_cert", "renewal requires the agent's current mTLS client certificate"));
        }

        Optional<AgentEnrollment> enrollment = tenants.enrollmentByAgentId(agentId);
        if (enrollment.isEmpty()) {
            log.warn("certificate renewal rejected: client cert CN '{}' is not enrolled", agentId);
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("not_enrolled", "the presented certificate identity is not enrolled"));
        }
        if (tenants.isAgentRevoked(agentId)) {
            log.warn("certificate renewal rejected: agent '{}' is revoked", agentId);
            return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("revoked", "this agent has been revoked"));
        }

        try {
            PKCS10CertificationRequest csr = Pem.parseCsr(csrPem);
            X509Certificate leaf = authority.sign(csr, agentId, LEAF_VALIDITY);
            String bundle = Pem.bundle(leaf, authority.caChain());
            log.info("renewed client certificate for agent '{}' (tenant '{}')", agentId, enrollment.get().tenantId());
            return ResponseEntity.ok().contentType(PEM).body(bundle);
        } catch (Exception e) {
            log.warn("certificate renewal failed for agent '{}': {}", agentId, e.toString());
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiError("bad_csr", "could not sign the certificate request: " + e.getMessage()));
        }
    }

    /** The CN of the request's verified mTLS client certificate, or {@code null} if none was presented. Mirrors
     *  {@code AgentAuthInterceptor}: identity is the cert's CN, never anything the client otherwise claims. */
    private static String clientCertCommonName(HttpServletRequest request) {
        Object attr = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attr instanceof X509Certificate[] certs) || certs.length == 0) {
            return null;
        }
        try {
            for (Rdn rdn : new LdapName(certs[0].getSubjectX500Principal().getName()).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return rdn.getValue().toString();
                }
            }
        } catch (Exception ignored) {
            // malformed subject — treat as no identity
        }
        return null;
    }
}
