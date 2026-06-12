package io.hivekeeper.gateway.access;

/**
 * The authenticated caller for one request, resolved by {@link AccessGuard}: either a human {@code user}
 * acting in an organization (authorization is checked against their grants) or a service {@code owner}
 * (the {@code X-Tenant-Key} principal, which has full rights in its tenant — for automation/CI). Both
 * carry the {@code tenantId} the request operates in.
 */
public record Principal(String tenantId, String userId, boolean owner) {

    public static Principal owner(String tenantId) {
        return new Principal(tenantId, null, true);
    }

    public static Principal user(String tenantId, String userId) {
        return new Principal(tenantId, userId, false);
    }
}
