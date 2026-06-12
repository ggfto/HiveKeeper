package io.hivekeeper.gateway.access;

/**
 * One scoped role a user holds within an organization: {@code role} at {@code scopeType}/{@code scopeId}.
 * {@code scopeId} is the site or group id, or {@code null} for an org-wide grant. A user may hold several
 * grants; {@link Permissions} folds them into an effective role per resource.
 */
public record Grant(Role role, ScopeType scopeType, String scopeId) {

    public Grant {
        if (role == null || scopeType == null) {
            throw new IllegalArgumentException("grant requires a role and scope type");
        }
        if (scopeType != ScopeType.ORG && (scopeId == null || scopeId.isBlank())) {
            throw new IllegalArgumentException("a " + scopeType + " grant requires a scopeId");
        }
    }

    public static Grant org(Role role) {
        return new Grant(role, ScopeType.ORG, null);
    }

    public static Grant site(Role role, String siteId) {
        return new Grant(role, ScopeType.SITE, siteId);
    }

    public static Grant group(Role role, String groupId) {
        return new Grant(role, ScopeType.GROUP, groupId);
    }
}
