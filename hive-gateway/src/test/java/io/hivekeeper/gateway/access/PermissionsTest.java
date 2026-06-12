package io.hivekeeper.gateway.access;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionsTest {

    private static final String SITE_A = "site-a";
    private static final String SITE_B = "site-b";
    private static final String GROUP_1 = "grp-1";
    private static final String GROUP_2 = "grp-2";

    // -- ORG scope reaches everything -------------------------------------------

    @Test
    void orgGrantCoversOrgSiteGroupAndDevice() {
        var grants = List.of(Grant.org(Role.ADMIN));

        assertEquals(Role.ADMIN, Permissions.effectiveRole(grants, ResourceScope.org()).orElseThrow());
        assertEquals(Role.ADMIN, Permissions.effectiveRole(grants, ResourceScope.site(SITE_A)).orElseThrow());
        assertEquals(Role.ADMIN, Permissions.effectiveRole(grants, ResourceScope.group(SITE_A, GROUP_1)).orElseThrow());
        assertEquals(Role.ADMIN, Permissions.effectiveRole(grants,
                ResourceScope.device(SITE_A, Set.of(GROUP_1, GROUP_2))).orElseThrow());
    }

    // -- SITE scope reaches its site's resources, nothing above or beside -------

    @Test
    void siteGrantCoversThatSiteAndItsDevicesButNotOrgOrOtherSites() {
        var grants = List.of(Grant.site(Role.OPERATOR, SITE_A));

        assertTrue(Permissions.allows(grants, Role.OPERATOR, ResourceScope.site(SITE_A)));
        assertTrue(Permissions.allows(grants, Role.OPERATOR, ResourceScope.device(SITE_A, Set.of(GROUP_1))));
        assertTrue(Permissions.allows(grants, Role.OPERATOR, ResourceScope.group(SITE_A, GROUP_1)));

        // not the organization itself, and not a different site
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.org()));
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.site(SITE_B)));
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.device(SITE_B, Set.of(GROUP_2))));
    }

    @Test
    void siteGrantDoesNotCoverAnOrgTagGroupWithNoSite() {
        var grants = List.of(Grant.site(Role.ADMIN, SITE_A));
        // an org-level tag group (null site) is not under any one site
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.group(null, GROUP_1)));
    }

    // -- GROUP scope reaches only its devices -----------------------------------

    @Test
    void groupGrantCoversDevicesTaggedIntoThatGroupOnly() {
        var grants = List.of(Grant.group(Role.VIEWER, GROUP_1));

        assertTrue(Permissions.allows(grants, Role.VIEWER, ResourceScope.device(SITE_A, Set.of(GROUP_1))));
        assertTrue(Permissions.allows(grants, Role.VIEWER, ResourceScope.device(SITE_B, Set.of(GROUP_1, GROUP_2))));

        // a device not in the group, the site, and the org are all out of reach
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.device(SITE_A, Set.of(GROUP_2))));
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.site(SITE_A)));
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.org()));
    }

    // -- folding multiple grants ------------------------------------------------

    @Test
    void effectiveRoleIsTheHighestCoveringGrant() {
        var grants = List.of(
                Grant.org(Role.VIEWER),            // covers everything, weakly
                Grant.site(Role.ADMIN, SITE_A),    // stronger, but only site A
                Grant.group(Role.OPERATOR, GROUP_1));

        // site A device: ADMIN (site) beats VIEWER (org) and OPERATOR (group)
        assertEquals(Role.ADMIN, Permissions.effectiveRole(grants,
                ResourceScope.device(SITE_A, Set.of(GROUP_1))).orElseThrow());
        // site B device tagged grp-1: OPERATOR (group) beats VIEWER (org); ADMIN(site A) does not apply
        assertEquals(Role.OPERATOR, Permissions.effectiveRole(grants,
                ResourceScope.device(SITE_B, Set.of(GROUP_1))).orElseThrow());
        // site B device, no relevant group: only the org VIEWER applies
        assertEquals(Role.VIEWER, Permissions.effectiveRole(grants,
                ResourceScope.device(SITE_B, Set.of(GROUP_2))).orElseThrow());
    }

    @Test
    void noCoveringGrantMeansNoAccess() {
        var grants = List.of(Grant.site(Role.OWNER, SITE_A));
        assertTrue(Permissions.effectiveRole(grants, ResourceScope.site(SITE_B)).isEmpty());
        assertFalse(Permissions.allows(grants, Role.VIEWER, ResourceScope.site(SITE_B)));
        assertFalse(Permissions.allows(List.of(), Role.VIEWER, ResourceScope.org()));
    }

    // -- role threshold ---------------------------------------------------------

    @Test
    void allowsEnforcesTheRequiredRankNotJustPresence() {
        var grants = List.of(Grant.org(Role.OPERATOR));
        assertTrue(Permissions.allows(grants, Role.VIEWER, ResourceScope.org()));
        assertTrue(Permissions.allows(grants, Role.OPERATOR, ResourceScope.org()));
        // operator cannot do admin/owner work
        assertFalse(Permissions.allows(grants, Role.ADMIN, ResourceScope.org()));
        assertFalse(Permissions.allows(grants, Role.OWNER, ResourceScope.org()));
    }

    @Test
    void roleRankingAndParsing() {
        assertTrue(Role.OWNER.atLeast(Role.ADMIN));
        assertTrue(Role.ADMIN.atLeast(Role.OPERATOR));
        assertTrue(Role.OPERATOR.atLeast(Role.VIEWER));
        assertFalse(Role.VIEWER.atLeast(Role.OPERATOR));
        assertEquals(Role.OPERATOR, Role.of("operator"));
        assertEquals(ScopeType.SITE, ScopeType.of("site"));
    }
}
