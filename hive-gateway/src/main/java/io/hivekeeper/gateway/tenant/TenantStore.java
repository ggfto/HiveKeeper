package io.hivekeeper.gateway.tenant;

import java.util.Optional;

/**
 * Tenant + enrollment lookups. v1 is in-memory; the production implementation is Postgres with
 * shared-schema + a non-null {@code tenant_id} + Row-Level Security, behind this same interface.
 */
public interface TenantStore {

    Optional<AgentEnrollment> enrollmentByToken(String token);

    /** Resolve a tenant by the (mTLS-derived or pre-issued) agent identity, for cross-checking. */
    Optional<AgentEnrollment> enrollmentByAgentId(String agentId);

    Optional<Tenant> tenantByApiKey(String apiKey);

    Optional<Tenant> tenant(String tenantId);

    /** The site an agent is bound to (its physical LAN), used to scope authorization of operations through
     *  it. Empty if the agent has no site or the store does not track sites (the in-memory default). */
    default Optional<String> agentSiteId(String agentId) {
        return Optional.empty();
    }
}
