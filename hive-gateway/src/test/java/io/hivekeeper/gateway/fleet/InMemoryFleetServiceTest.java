package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.ResourceScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev/demo fleet store in isolation: it mirrors the Postgres path's observable behaviour (tenant
 * isolation, unique site name + device serial, group tags that cascade on delete, the dependent count that
 * blocks a non-empty site delete) and reports the one gap (enrollment) as unsupported. The Postgres impl is
 * covered separately by GatewayPostgresIT against a real database.
 */
class InMemoryFleetServiceTest {

    private final InMemoryFleetService fleet = new InMemoryFleetService(false, false);

    @Test
    void createsListsRenamesAndDeletesSites() {
        String id = fleet.createSite("acme", "HQ");
        assertTrue(fleet.siteExists("acme", id));
        assertEquals(List.of("HQ"), fleet.listSites("acme").stream().map(FleetService.Site::name).toList());
        fleet.renameSite("acme", id, "HQ2");
        assertEquals("HQ2", fleet.listSites("acme").get(0).name());
        fleet.deleteSite("acme", id);
        assertFalse(fleet.siteExists("acme", id));
    }

    @Test
    void rejectsDuplicateSiteNamesWithinATenant() {
        fleet.createSite("acme", "HQ");
        assertThrows(DuplicateKeyException.class, () -> fleet.createSite("acme", "HQ"));
    }

    @Test
    void isolatesTenants() {
        fleet.createSite("acme", "HQ");
        assertEquals(0, fleet.listSites("globex").size());
        assertDoesNotThrow(() -> fleet.createSite("globex", "HQ")); // same name, different org is fine
    }

    @Test
    void countsDependentsThatBlockASiteDelete() {
        String site = fleet.createSite("acme", "HQ");
        fleet.createGroup("acme", "Floor 3", site);
        fleet.registerDevice("acme", "SER-1", "AP230", "Lab", "10.0.0.1", site, "lab-agent", "cred");
        assertEquals(2, fleet.siteDependents("acme", site)); // one group + one device
    }

    @Test
    void tagsDevicesIntoGroupsScopesThemAndCascadesOnGroupDelete() {
        String site = fleet.createSite("acme", "HQ");
        String group = fleet.createGroup("acme", "Floor 3", site);
        String dev = fleet.registerDevice("acme", "SER-1", "AP230", "Lab", "10.0.0.1", site, "lab-agent", "cred");
        fleet.tagDevice("acme", dev, group);

        assertEquals(List.of(dev),
                fleet.devicesFor("acme", null, group).stream().map(FleetService.Device::deviceId).toList());
        assertEquals(Optional.of(ResourceScope.device(site, Set.of(group))), fleet.deviceScope("acme", dev));

        fleet.deleteGroup("acme", group);
        assertTrue(fleet.devicesFor("acme", null, group).isEmpty()); // tag gone
        assertEquals(1, fleet.listDevices("acme").size());           // device remains
    }

    @Test
    void updateGroupRenamesAndRePinsItAcrossSites() {
        String a = fleet.createSite("acme", "A");
        String b = fleet.createSite("acme", "B");
        String g = fleet.createGroup("acme", "Floor", a);
        assertEquals(Optional.of(ResourceScope.group(a, g)), fleet.groupScope("acme", g));

        fleet.updateGroup("acme", g, "Floor 9", b);   // rename + move to site B
        assertEquals("Floor 9", fleet.listGroups("acme").get(0).name());
        assertEquals(Optional.of(ResourceScope.group(b, g)), fleet.groupScope("acme", g));

        fleet.updateGroup("acme", g, "Floor 9", null); // turn it into a cross-site tag
        assertEquals(Optional.of(ResourceScope.group(null, g)), fleet.groupScope("acme", g));
    }

    @Test
    void reAdoptingASerialUpdatesTheOneRowAndAddsBothAgentsToTheReachableSet() {
        // Re-adopting an AP from a second, backup agent converges to one device row — the same id comes back —
        // and ADDS that agent to the reachability set (both can drive it) rather than displacing the first. A
        // re-adopt with no credential keeps the existing one.
        String first = fleet.registerDevice("acme", "SER-1", "AP230", "lab", "10.0.0.1", "site-1", "agent-a", "cred-a");
        String again = fleet.registerDevice("acme", "SER-1", "AP410C", "lab-2", "10.0.0.2", "site-1", "agent-b", null);

        assertEquals(first, again);
        assertEquals(1, fleet.listDevices("acme").stream().filter(d -> "SER-1".equals(d.serial())).count());
        var d = fleet.listDevices("acme").stream().filter(x -> "SER-1".equals(x.serial())).findFirst().orElseThrow();
        assertEquals(List.of("agent-a", "agent-b"), d.reachableAgents());
        assertEquals("cred-a", d.credRef());
    }

    @Test
    void enrollmentIsNotSupportedInTheDemoStack() {
        assertThrows(UnsupportedOperationException.class, () -> fleet.createEnrollment("acme", "agent-x", null));
    }
}
