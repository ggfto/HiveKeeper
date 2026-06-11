package io.hivekeeper.gateway.tenant;

/**
 * Binds an agent identity to a tenant. The {@code token} is the credential the agent presents to
 * connect (a bearer enrollment token in this v1; mTLS hardening derives the same {@code agentId} from
 * the client certificate instead). Crucially, the gateway derives {@code tenantId} from THIS record —
 * never from anything the agent says in its {@code Hello}.
 */
public record AgentEnrollment(String token, String agentId, String tenantId) {
}
