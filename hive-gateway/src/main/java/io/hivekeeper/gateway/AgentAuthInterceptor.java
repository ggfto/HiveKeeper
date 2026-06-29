package io.hivekeeper.gateway;

import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates an agent at the WebSocket handshake and stamps its VERIFIED identity (agentId +
 * tenantId) into the session — derived server-side from an enrollment record, never from anything the
 * agent later claims in its {@code Hello}. v1 verifies a bearer enrollment token on the URL; the mTLS
 * hardening verifies a client certificate here instead (same outcome: a server-trusted identity).
 */
@Component
@Slf4j
public class AgentAuthInterceptor implements HandshakeInterceptor {

    static final String ATTR_AGENT_ID = "hk.agentId";
    static final String ATTR_TENANT_ID = "hk.tenantId";
    static final String ATTR_AGENT_PUBKEY = "hk.agentPublicKey";

    private final TenantStore tenants;

    AgentAuthInterceptor(TenantStore tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // Preferred: mutual TLS — identity comes from the client certificate's CN.
        X509Certificate clientCert = clientCertificate(request);
        if (clientCert != null) {
            String cn = commonName(clientCert);
            Optional<AgentEnrollment> byCert = tenants.enrollmentByAgentId(cn);
            if (byCert.isPresent()) {
                stamp(attributes, byCert.get());
                // Cache the agent's public key (from its verified cert) so the gateway can seal credentials
                // TO this agent — end-to-end, so the cloud never holds a usable plaintext secret.
                attributes.put(ATTR_AGENT_PUBKEY, clientCert.getPublicKey());
                log.info("agent authenticated via mTLS (CN={})", cn);
                return true;
            }
            log.warn("rejecting agent handshake: client cert CN '{}' is not enrolled", cn);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Fallback: bearer enrollment token on the URL (dev / pre-mTLS bootstrap).
        Optional<AgentEnrollment> byToken = tenants.enrollmentByToken(queryParam(request.getURI(), "token"));
        if (byToken.isEmpty()) {
            log.warn("rejecting agent handshake: no client cert and invalid/missing enrollment token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        stamp(attributes, byToken.get());
        return true;
    }

    private static void stamp(Map<String, Object> attributes, AgentEnrollment enrollment) {
        attributes.put(ATTR_AGENT_ID, enrollment.agentId());
        attributes.put(ATTR_TENANT_ID, enrollment.tenantId());
    }

    private static X509Certificate clientCertificate(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Object attr = servletRequest.getServletRequest()
                    .getAttribute("jakarta.servlet.request.X509Certificate");
            if (attr instanceof X509Certificate[] certs && certs.length > 0) {
                return certs[0];
            }
        }
        return null;
    }

    private static String commonName(X509Certificate cert) {
        try {
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return rdn.getValue().toString();
                }
            }
        } catch (Exception ignored) {
            // malformed subject — treat as no identity
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
