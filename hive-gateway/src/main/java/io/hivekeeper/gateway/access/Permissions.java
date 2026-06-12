package io.hivekeeper.gateway.access;

import java.util.Collection;
import java.util.Optional;

/**
 * The effective-role resolver — the heart of intra-organization authorization. Given the grants a user
 * holds and the lineage of a resource, it folds the covering grants into the single highest role that
 * applies, implementing scope inheritance (org &gt; site &gt; group). This is pure logic with no database or
 * Spring dependency, so it can be exhaustively unit-tested; callers assemble the {@link ResourceScope} from
 * persisted lineage and ask {@link #allows}.
 *
 * <p>Note this answers authorization WITHIN one organization only. Isolation BETWEEN organizations is a
 * separate, harder wall enforced by Postgres row-level security — never by this class.
 */
public final class Permissions {

    private Permissions() {
    }

    /** The highest role any covering grant confers on the resource, or empty if no grant reaches it. */
    public static Optional<Role> effectiveRole(Collection<Grant> grants, ResourceScope resource) {
        Role best = null;
        for (Grant grant : grants) {
            if (covers(grant, resource) && (best == null || grant.role().rank() > best.rank())) {
                best = grant.role();
            }
        }
        return Optional.ofNullable(best);
    }

    /** True if the user's grants confer at least {@code required} on the resource. */
    public static boolean allows(Collection<Grant> grants, Role required, ResourceScope resource) {
        return effectiveRole(grants, resource).filter(role -> role.atLeast(required)).isPresent();
    }

    private static boolean covers(Grant grant, ResourceScope resource) {
        return switch (grant.scopeType()) {
            case ORG -> true;                                                  // reaches everything in the org
            case SITE -> resource.siteId() != null && resource.siteId().equals(grant.scopeId());
            case GROUP -> resource.groupIds().contains(grant.scopeId());
        };
    }
}
