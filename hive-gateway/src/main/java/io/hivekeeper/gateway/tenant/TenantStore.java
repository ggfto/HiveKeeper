package io.hivekeeper.gateway.tenant;

import java.util.List;
import java.util.Optional;

/**
 * Tenant + enrollment lookups. v1 is in-memory; the production implementation is Postgres with
 * shared-schema + a non-null {@code tenant_id} + Row-Level Security, behind this same interface.
 */
public interface TenantStore {

    Optional<AgentEnrollment> enrollmentByToken(String token);

    /** Resolve a tenant by the (mTLS-derived or pre-issued) agent identity, for cross-checking. */
    Optional<AgentEnrollment> enrollmentByAgentId(String agentId);

    /**
     * Atomically consume a one-time enrollment token: mark it used iff it was not already used. Returns
     * {@code true} only when THIS call transitioned an unconsumed token to consumed — so the certificate
     * bootstrap can mint exactly one cert per token and reject a reused/raced token. The default fails closed
     * ({@code false}) for stores that do not track consumption.
     */
    default boolean markEnrollmentConsumed(String token) {
        return false;
    }

    Optional<Tenant> tenantByApiKey(String apiKey);

    Optional<Tenant> tenant(String tenantId);

    /** The site an agent is bound to (its physical LAN), used to scope authorization of operations through
     *  it. Empty if the agent has no site or the store does not track sites (the in-memory default). */
    default Optional<String> agentSiteId(String agentId) {
        return Optional.empty();
    }

    /** Every tenant — used by the background fleet poller to scan each org in turn. Cross-tenant by nature
     *  (the poller runs unattended, not on behalf of one caller). The in-memory default returns none. */
    default List<Tenant> listAllTenants() {
        return List.of();
    }
}
