package io.hivekeeper.gateway.access;

/**
 * The authenticated caller for one request, resolved by {@link AccessGuard}: either a human {@code user}
 * acting in an organization (authorization is checked against their grants) or a {@code service} principal
 * (the {@code X-Tenant-Key} caller) that holds one fixed, tenant-wide {@code serviceRole} — usually OWNER (full
 * rights, for automation/CI), but a key can be issued a lower role. Both carry the {@code tenantId} the request
 * operates in. {@code serviceRole == null} marks a human user.
 */
public record Principal(String tenantId, String userId, Role serviceRole) {

    /** A service principal (X-Tenant-Key) with a fixed, tenant-wide role. */
    public static Principal service(String tenantId, Role role) {
        return new Principal(tenantId, null, role);
    }

    /** Convenience: a full-rights service principal. */
    public static Principal owner(String tenantId) {
        return service(tenantId, Role.OWNER);
    }

    /** A human user — authorization is resolved from their scoped grants. */
    public static Principal user(String tenantId, String userId) {
        return new Principal(tenantId, userId, null);
    }

    /** Whether this is a service (X-Tenant-Key) principal rather than a human user. */
    public boolean isService() {
        return serviceRole != null;
    }
}
