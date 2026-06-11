package io.hivekeeper.gateway;

import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    private final TenantStore tenants;

    AgentAuthInterceptor(TenantStore tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = queryParam(request.getURI(), "token");
        Optional<AgentEnrollment> enrollment = tenants.enrollmentByToken(token);
        if (enrollment.isEmpty()) {
            log.warn("rejecting agent handshake: invalid/missing enrollment token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_AGENT_ID, enrollment.get().agentId());
        attributes.put(ATTR_TENANT_ID, enrollment.get().tenantId());
        return true;
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
