package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP enforcement contract of the fleet endpoints: a guard rejection renders the right status +
 * {error,detail} JSON (via {@link AccessExceptionAdvice}), and each endpoint demands the correct role on the
 * correct scope. The guard is mocked so this is a fast, DB-free slice test.
 */
@WebMvcTest(FleetController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("postgres")
class FleetControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccessGuard guard;
    @MockitoBean
    private FleetService fleet;
    @MockitoBean
    private AccessService access;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() {
        when(guard.authenticate()).thenReturn(principal);
    }

    // -- the advice renders the guard's status + body ---------------------------

    @Test
    void aGuardRejectionRendersStatusAndJsonBody() throws Exception {
        doThrow(new AccessException(403, "forbidden", "requires ADMIN")).when(guard).require(any(), any(), any());
        mvc.perform(get("/api/sites"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.detail").value("requires ADMIN"));
    }

    @Test
    void anAuthenticationFailureRendersIts401() throws Exception {
        when(guard.authenticate()).thenThrow(new AccessException(401, "unauthorized", "no creds"));
        mvc.perform(get("/api/sites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // -- each endpoint requires the right role on the right scope ---------------

    @Test
    void listSitesRequiresViewerOnTheOrg() throws Exception {
        when(fleet.listSites("acme")).thenReturn(List.of());
        mvc.perform(get("/api/sites")).andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
    }

    @Test
    void createSiteRequiresAdminOnTheOrg() throws Exception {
        when(fleet.createSite(eq("acme"), any())).thenReturn("site-1");
        mvc.perform(post("/api/sites").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"HQ\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void createGroupOnASiteRequiresAdminOnThatSite() throws Exception {
        when(fleet.createGroup(eq("acme"), any(), eq("s1"))).thenReturn("grp-1");
        mvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Floor 3\",\"siteId\":\"s1\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("s1")));
    }

    @Test
    void enrollAgentRequiresAdminOnTheOrgAndReturnsTheToken() throws Exception {
        when(fleet.createEnrollment(eq("acme"), eq("agent-1"), eq(null))).thenReturn("enroll-xyz");
        mvc.perform(post("/api/enrollments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("agent-1"))
                .andExpect(jsonPath("$.token").value("enroll-xyz"));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void enrollAgentPinnedToASiteRequiresAdminOnThatSite() throws Exception {
        when(fleet.createEnrollment(eq("acme"), eq("agent-2"), eq("s1"))).thenReturn("enroll-2");
        mvc.perform(post("/api/enrollments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-2\",\"siteId\":\"s1\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("s1")));
    }

    @Test
    void tagDeviceRequiresAdminOnBothTheDeviceAndTheTargetGroup() throws Exception {
        ResourceScope deviceScope = ResourceScope.device("s1", Set.of("g0"));
        ResourceScope groupScope = ResourceScope.group("s2", "g1");
        when(fleet.deviceScope("acme", "d1")).thenReturn(Optional.of(deviceScope));
        when(fleet.groupScope("acme", "g1")).thenReturn(Optional.of(groupScope));

        mvc.perform(post("/api/devices/d1/groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"g1\"}"))
                .andExpect(status().isOk());

        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(deviceScope));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(groupScope));   // the security fix
    }

    @Test
    void untagDeviceRequiresAdminOnBothTheDeviceAndTheGroup() throws Exception {
        ResourceScope deviceScope = ResourceScope.device("s1", Set.of("g0"));
        ResourceScope groupScope = ResourceScope.group("s2", "g1");
        when(fleet.deviceScope("acme", "d1")).thenReturn(Optional.of(deviceScope));
        when(fleet.groupScope("acme", "g1")).thenReturn(Optional.of(groupScope));

        mvc.perform(delete("/api/devices/d1/groups/g1")).andExpect(status().isOk());

        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(deviceScope));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(groupScope));
    }

    @Test
    void updateDeviceRequiresAdminOnTheDeviceAndOnTheTargetSiteWhenMoving() throws Exception {
        ResourceScope deviceScope = ResourceScope.device("s1", Set.of());
        when(fleet.deviceScope("acme", "d1")).thenReturn(Optional.of(deviceScope));

        mvc.perform(patch("/api/devices/d1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Lab AP\",\"siteId\":\"s2\"}"))
                .andExpect(status().isOk());

        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(deviceScope));          // edit the device
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("s2"))); // move into the site
    }

    // -- sites: edit / delete ---------------------------------------------------

    @Test
    void renameSiteRequiresAdminOnTheOrg() throws Exception {
        when(fleet.siteExists("acme", "s1")).thenReturn(true);
        mvc.perform(patch("/api/sites/s1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"HQ2\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
        verify(fleet).renameSite("acme", "s1", "HQ2");
    }

    @Test
    void renamingAnUnknownSiteIs404() throws Exception {
        when(fleet.siteExists("acme", "ghost")).thenReturn(false);
        mvc.perform(patch("/api/sites/ghost").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAnEmptySiteRequiresAdminAndSucceeds() throws Exception {
        when(fleet.siteExists("acme", "s1")).thenReturn(true);
        when(fleet.siteDependents("acme", "s1")).thenReturn(0);
        mvc.perform(delete("/api/sites/s1")).andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
        verify(fleet).deleteSite("acme", "s1");
    }

    @Test
    void deletingASiteThatStillHasDevicesOrGroupsIsRefused() throws Exception {
        when(fleet.siteExists("acme", "s1")).thenReturn(true);
        when(fleet.siteDependents("acme", "s1")).thenReturn(3);
        mvc.perform(delete("/api/sites/s1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("site_not_empty"));
        verify(fleet, never()).deleteSite(any(), any());
    }

    // -- groups: edit / delete --------------------------------------------------

    @Test
    void renameGroupRequiresAdminOnTheGroupScope() throws Exception {
        ResourceScope groupScope = ResourceScope.group("s2", "g1");
        when(fleet.groupScope("acme", "g1")).thenReturn(Optional.of(groupScope));
        mvc.perform(patch("/api/groups/g1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Floor 9\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(groupScope));
        verify(fleet).renameGroup("acme", "g1", "Floor 9");
    }

    @Test
    void deleteGroupRequiresAdminOnTheGroupScope() throws Exception {
        ResourceScope groupScope = ResourceScope.group("s2", "g1");
        when(fleet.groupScope("acme", "g1")).thenReturn(Optional.of(groupScope));
        mvc.perform(delete("/api/groups/g1")).andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(groupScope));
        verify(fleet).deleteGroup("acme", "g1");
    }

    @Test
    void editingAnUnknownGroupIs404() throws Exception {
        when(fleet.groupScope("acme", "ghost")).thenReturn(Optional.empty());
        mvc.perform(delete("/api/groups/ghost")).andExpect(status().isNotFound());
    }
}
