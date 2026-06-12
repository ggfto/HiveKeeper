package io.hivekeeper.gateway.access;

import java.util.Set;

/**
 * The lineage of the resource being checked, within an organization: which site it sits in (if any) and
 * which groups it belongs to. A {@link Grant} covers the resource if its scope matches this lineage —
 * which is how an org grant reaches everything, a site grant reaches its devices, and a group grant reaches
 * a device tagged into that group. (The organization itself is implicit; cross-org access is walled off by
 * the database, not here.)
 */
public record ResourceScope(String siteId, Set<String> groupIds) {

    public ResourceScope {
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** The organization as a whole (managing users, creating sites): only ORG grants cover this. */
    public static ResourceScope org() {
        return new ResourceScope(null, Set.of());
    }

    /** A site (and, for coverage, anything under it). */
    public static ResourceScope site(String siteId) {
        return new ResourceScope(siteId, Set.of());
    }

    /** A group — site-pinned groups also match a SITE grant on their site; org-tag groups (null site) do not. */
    public static ResourceScope group(String siteId, String groupId) {
        return new ResourceScope(siteId, Set.of(groupId));
    }

    /** A device: its site plus every group it is tagged into. */
    public static ResourceScope device(String siteId, Set<String> groupIds) {
        return new ResourceScope(siteId, groupIds);
    }
}
