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

    /**
     * True when the agent's enrollment has been revoked (decommissioned or compromised). The auth seam and the
     * certificate-renewal endpoint refuse a revoked agent, so a single mark blocks every certificate it could
     * present (the CN=agentId identity is stable across renewals). Cross-tenant lookup — the {@code agentId} is
     * globally unique. The default never revokes (stores that do not track revocation).
     */
    default boolean isAgentRevoked(String agentId) {
        return false;
    }

    /**
     * Revoke an agent within {@code tenantId} (idempotent). Returns {@code true} when the agent exists in that
     * tenant (and is now revoked), {@code false} when it does not. The default throws — stores that cannot
     * mutate enrollments (the seed-only in-memory demo stack) do not support revocation.
     */
    default boolean revokeAgent(String tenantId, String agentId, String reason) {
        throw new UnsupportedOperationException("this tenant store cannot revoke agents");
    }

    /**
     * Re-enroll an agent within {@code tenantId}: clear its revoked/consumed marks and issue a FRESH one-time
     * token, returning it (so an operator can provision a replacement agent). Returns empty when the agent does
     * not exist in the tenant. The default throws — stores that cannot mint enrollments do not support it.
     */
    default Optional<String> reEnrollAgent(String tenantId, String agentId) {
        throw new UnsupportedOperationException("this tenant store cannot re-enroll agents");
    }

    Optional<Tenant> tenantByApiKey(String apiKey);

    Optional<Tenant> tenant(String tenantId);

    /** The site an agent is bound to (its physical LAN), used to scope authorization of operations through
     *  it. Empty if the agent has no site or the store does not track sites (the in-memory default). */
    default Optional<String> agentSiteId(String agentId) {
        return Optional.empty();
    }

    /**
     * Every agent enrolled to a site (excluding revoked ones), sorted so the ordering is stable. Two agents
     * on one site are the active/standby pair: intersect this with the connected set and the first is the
     * primary. The in-memory default returns none, so dev/demo keeps the single-pinned-agent behaviour.
     */
    default List<String> agentIdsForSite(String tenantId, String siteId) {
        return List.of();
    }

    /** Every tenant — used by the background fleet poller to scan each org in turn. Cross-tenant by nature
     *  (the poller runs unattended, not on behalf of one caller). The in-memory default returns none. */
    default List<Tenant> listAllTenants() {
        return List.of();
    }
}
