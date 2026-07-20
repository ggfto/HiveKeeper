package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.backup.BackupDestinationService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that each agent operation demands the right role on the agent's SITE — reads need viewer, writes need
 * operator, durable-job role follows the job type — that the agent list is filtered to what the caller can
 * view, and that an agent in another tenant is a 404 (never authorized against a foreign site). The guard is
 * mocked, so this is a fast slice test; the verify() also confirms require() ran on the request thread for
 * the async endpoints.
 */
@WebMvcTest(GatewayController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
class GatewayControllerSecurityTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccessGuard guard;
    @MockitoBean
    private AgentRegistry registry;
    @MockitoBean
    private TenantStore tenants;
    @MockitoBean
    private FleetService fleet;
    @MockitoBean
    private BackupDestinationService backupDestinations;
    @MockitoBean
    private BackupDestinationProvisioner backupProvisioner;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() {
        when(guard.authenticate()).thenReturn(principal);
        // lab-agent belongs to the caller's tenant (acme) and is on site-1
        when(tenants.enrollmentByAgentId("lab-agent")).thenReturn(Optional.of(new AgentEnrollment("t", "lab-agent", "acme")));
        when(tenants.agentSiteId("lab-agent")).thenReturn(Optional.of("site-1"));
    }

    // -- role per endpoint ------------------------------------------------------

    @Test
    void inventoryRequiresViewerOnTheAgentSite() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/inventory").contentType(JSON).content("{\"host\":\"10.0.0.1\"}"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.site("site-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/agents/lab-agent/backup", "/api/agents/lab-agent/discover",
            "/api/agents/lab-agent/inventory/stream"})
    void agentScopedReadsRequireViewerOnTheAgentSite(String path) throws Exception {
        String body = path.endsWith("/discover") ? "{\"cidr\":\"10.0.0.0/24\"}" : "{\"host\":\"10.0.0.1\"}";
        mvc.perform(post(path).contentType(JSON).content(body));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.site("site-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/agents/lab-agent/configure-ssid", "/api/agents/lab-agent/configure-hive",
            "/api/agents/lab-agent/reboot", "/api/agents/lab-agent/apply-config"})
    void agentScopedWritesRequireOperatorOnTheAgentSite(String path) throws Exception {
        mvc.perform(post(path).contentType(JSON)
                .content("{\"host\":\"10.0.0.1\",\"name\":\"X\",\"psk\":\"pw\",\"password\":\"pw\"}"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.site("site-1")));
    }

    @ParameterizedTest
    @CsvSource({"configure-ssid,OPERATOR", "configure-hive,OPERATOR",
            "inventory,VIEWER", "backup,VIEWER", "discover,VIEWER", "frobnicate,VIEWER"})
    void durableJobRoleFollowsTheJobType(String type, String role) throws Exception {
        mvc.perform(post("/api/agents/lab-agent/jobs").contentType(JSON).content("{\"type\":\"" + type + "\"}"));
        verify(guard).require(eq(principal), eq(Role.valueOf(role)), eq(ResourceScope.site("site-1")));
    }

    @Test
    void orgWideReadsRequireViewerOnTheOrg() throws Exception {
        mvc.perform(get("/api/operations"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
        mvc.perform(get("/api/jobs/j1"));
        verify(guard, org.mockito.Mockito.times(2)).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
    }

    @Test
    void anAgentWithNoSiteIsAuthorizedAtTheOrgScope() throws Exception {
        when(tenants.enrollmentByAgentId("homeless")).thenReturn(Optional.of(new AgentEnrollment("t", "homeless", "acme")));
        when(tenants.agentSiteId("homeless")).thenReturn(Optional.empty());
        mvc.perform(post("/api/agents/homeless/inventory").contentType(JSON).content("{\"host\":\"10.0.0.1\"}"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
    }

    // -- cross-tenant + denial --------------------------------------------------

    @Test
    void anAgentInAnotherTenantIs404NeverAuthorizedAgainstItsSite() throws Exception {
        when(tenants.enrollmentByAgentId("foreign")).thenReturn(Optional.of(new AgentEnrollment("t", "foreign", "globex")));
        mvc.perform(post("/api/agents/foreign/inventory").contentType(JSON).content("{\"host\":\"10.0.0.1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("agent_not_found")));
    }

    // -- agent certificate lifecycle: revoke / re-enroll are admin-only ---------

    @Test
    void revokeRequiresAdminOnTheAgentSite() throws Exception {
        when(tenants.revokeAgent("acme", "lab-agent", null)).thenReturn(true);
        mvc.perform(post("/api/agents/lab-agent/revoke").contentType(JSON).content("{}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("site-1")));
    }

    @Test
    void reEnrollRequiresAdminAndReturnsAFreshToken() throws Exception {
        when(tenants.reEnrollAgent("acme", "lab-agent")).thenReturn(Optional.of("enroll-fresh"));
        mvc.perform(post("/api/agents/lab-agent/re-enroll"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("enroll-fresh")));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("site-1")));
    }

    @Test
    void revokingAnAgentInAnotherTenantIs404() throws Exception {
        when(tenants.enrollmentByAgentId("foreign")).thenReturn(Optional.of(new AgentEnrollment("t", "foreign", "globex")));
        mvc.perform(post("/api/agents/foreign/revoke").contentType(JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("agent_not_found")));
    }

    @Test
    void aDeniedWriteRendersA403() throws Exception {
        doThrow(new AccessException(403, "forbidden", "requires OPERATOR"))
                .when(guard).require(any(), eq(Role.OPERATOR), any());
        mvc.perform(post("/api/agents/lab-agent/reboot").contentType(JSON).content("{\"host\":\"10.0.0.1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentListIsFilteredToTheSitesTheCallerCanView() throws Exception {
        when(registry.agentIds("acme")).thenReturn(Set.of("a-visible", "a-hidden"));
        when(tenants.agentSiteId("a-visible")).thenReturn(Optional.of("s-vis"));
        when(tenants.agentSiteId("a-hidden")).thenReturn(Optional.of("s-hid"));
        when(guard.allows(principal, Role.VIEWER, ResourceScope.site("s-vis"))).thenReturn(true);
        when(guard.allows(principal, Role.VIEWER, ResourceScope.site("s-hid"))).thenReturn(false);

        mvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("a-visible")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("a-hidden"))));
    }

    // -- bulk fleet ops: scope mapping, op whitelist, per-device re-authorization, credRef wiring ----------

    @Test
    void bulkGroupTargetAuthorizesAgainstTheGroupsOwnScope() throws Exception {
        when(fleet.groupScope("acme", "g1")).thenReturn(Optional.of(ResourceScope.group("s1", "g1")));
        mvc.perform(post("/api/fleet/bulk/inventory").contentType(JSON).content("{\"groupId\":\"g1\"}"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.group("s1", "g1")));
    }

    @Test
    void bulkSiteTargetAuthorizesAgainstTheSiteScope() throws Exception {
        mvc.perform(post("/api/fleet/bulk/inventory").contentType(JSON).content("{\"siteId\":\"s1\"}"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.site("s1")));
    }

    @Test
    void bulkOrgTargetAuthorizesAgainstTheOrgScope() throws Exception {
        mvc.perform(post("/api/fleet/bulk/inventory").contentType(JSON).content("{}"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
    }

    @Test
    void bulkRejectsAnOpThatIsNotBackupOrInventory() throws Exception {
        when(fleet.devicesFor(any(), any(), any())).thenReturn(List.of());
        MvcResult result = mvc.perform(post("/api/fleet/bulk/reboot").contentType(JSON).content("{}")).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("op must be backup or inventory")));
    }

    @Test
    void bulkReAuthorizesEachDeviceAndSkipsOnesTheCallerCannotView() throws Exception {
        // The scope-level check (group/site/org) passed, but a cross-site GROUP can contain a device pinned to
        // another site. Bulk must re-check each device against its OWN lineage and never touch a forbidden one.
        FleetService.Device viewable = new FleetService.Device(
                "dev-A", "site-1", "lab-agent", "SER-A", "AP230", "A", "10.0.0.1", null, List.of());
        FleetService.Device foreign = new FleetService.Device(
                "dev-B", "site-2", "lab-agent", "SER-B", "AP230", "B", "10.0.0.2", null, List.of());
        when(fleet.devicesFor("acme", null, null)).thenReturn(List.of(viewable, foreign));
        when(guard.allows(eq(principal), eq(Role.VIEWER), eq(ResourceScope.device("site-1", Set.of())))).thenReturn(true);
        // the site-2 device: guard.allows defaults to false -> "forbidden", never dispatched
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.empty());   // viewable -> agent_offline

        MvcResult result = mvc.perform(post("/api/fleet/bulk/inventory").contentType(JSON).content("{}")).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("dev-B")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("forbidden")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("agent_offline")));
        // the forbidden device short-circuited BEFORE dispatch: engine() was looked up only for the viewable one
        verify(registry, times(1)).engine("acme", "lab-agent");
    }

    @Test
    void bulkAttachesEachDevicesOwnCredRefToTheDispatchedCommand() throws Exception {
        FleetService.Device d = new FleetService.Device(
                "dev-A", "site-1", "lab-agent", "SER-A", "AP230", "A", "10.0.0.1", "cred-x", List.of());
        when(fleet.devicesFor("acme", null, null)).thenReturn(List.of(d));
        when(guard.allows(eq(principal), eq(Role.VIEWER), any())).thenReturn(true);
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any()))
                .thenReturn(new Result.Discovered(UUID.randomUUID(), DeviceId.of("10.0.0.1"), List.of()));

        MvcResult result = mvc.perform(post("/api/fleet/bulk/inventory").contentType(JSON).content("{}")).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(engine).execute(cmd.capture(), any());
        // the cloud sends ONLY the reference: the dispatched command carries the device row's own credRef
        DeviceRef ref = ((Command.Inventory) cmd.getValue()).device();
        assertEquals("cred-x", ref.credRef());
    }

    // -- bulk apply-config: a WRITE, so operator-level, validated, and re-authorized per device --------------

    @Test
    void bulkApplyConfigRequiresOperatorOnTheTargetScope() throws Exception {
        mvc.perform(post("/api/fleet/bulk/apply-config").contentType(JSON).content("{\"commands\":[\"hostname X\"]}"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.org()));
    }

    @Test
    void bulkApplyConfigSiteTargetAuthorizesAgainstTheSiteScope() throws Exception {
        mvc.perform(post("/api/fleet/bulk/apply-config").contentType(JSON)
                .content("{\"siteId\":\"s1\",\"commands\":[\"hostname X\"]}"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.site("s1")));
    }

    @Test
    void bulkApplyConfigRejectsEmptyCommands() throws Exception {
        MvcResult result = mvc.perform(post("/api/fleet/bulk/apply-config").contentType(JSON).content("{}")).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("at least one CLI command is required")));
    }

    @Test
    void bulkApplyConfigReAuthorizesEachDeviceAtOperatorAndSkipsForbidden() throws Exception {
        // A cross-site group can carry a device pinned to another site; bulk apply-config must re-check each
        // device at OPERATOR against its OWN lineage and never configure a forbidden one.
        FleetService.Device writable = new FleetService.Device(
                "dev-A", "site-1", "lab-agent", "SER-A", "AP230", "A", "10.0.0.1", null, List.of());
        FleetService.Device foreign = new FleetService.Device(
                "dev-B", "site-2", "lab-agent", "SER-B", "AP230", "B", "10.0.0.2", null, List.of());
        when(fleet.devicesFor("acme", null, null)).thenReturn(List.of(writable, foreign));
        when(guard.allows(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.device("site-1", Set.of())))).thenReturn(true);
        // the site-2 device: guard.allows defaults to false -> "forbidden", never dispatched
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.empty());   // writable -> agent_offline

        MvcResult result = mvc.perform(post("/api/fleet/bulk/apply-config").contentType(JSON)
                .content("{\"commands\":[\"hostname X\"]}")).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("dev-B")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("forbidden")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("agent_offline")));
        verify(registry, times(1)).engine("acme", "lab-agent");
    }

    @Test
    void bulkApplyConfigDispatchesApplyConfigWithTheDevicesOwnCredRefAndSaveFlag() throws Exception {
        FleetService.Device d = new FleetService.Device(
                "dev-A", "site-1", "lab-agent", "SER-A", "AP230", "A", "10.0.0.1", "cred-x", List.of());
        when(fleet.devicesFor("acme", null, null)).thenReturn(List.of(d));
        when(guard.allows(eq(principal), eq(Role.OPERATOR), any())).thenReturn(true);
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any()))
                .thenReturn(new Result.ConfigApplied(UUID.randomUUID(), DeviceId.of("10.0.0.1"), List.of("hostname X"), List.of(), true));

        MvcResult result = mvc.perform(post("/api/fleet/bulk/apply-config").contentType(JSON)
                .content("{\"commands\":[\" hostname X \",\"\"],\"save\":true}")).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(engine).execute(cmd.capture(), any());
        Command.ApplyConfig applied = (Command.ApplyConfig) cmd.getValue();
        assertEquals("cred-x", applied.device().credRef());
        assertEquals(List.of("hostname X"), applied.commands());   // blanks stripped server-side
        org.junit.jupiter.api.Assertions.assertTrue(applied.save());
    }

    // -- set-credential: admin-only, sealed to the agent key, never persisted -------------------------------

    private static final String CRED_BODY =
            "{\"host\":\"10.0.0.1\",\"deviceId\":\"dev-A\",\"username\":\"admin\",\"password\":\"sup3r-secret\"}";

    @Test
    void setCredentialRequiresAdminOnTheAgentSite() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/set-credential").contentType(JSON).content(CRED_BODY));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.site("site-1")));
    }

    @Test
    void setCredentialSealsTheSecretToTheAgentKeyAndLeaksNoPlaintext() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair agentKey = kpg.generateKeyPair();
        when(registry.publicKey("acme", "lab-agent")).thenReturn(Optional.of(agentKey.getPublic()));
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any()))
                .thenReturn(new Result.CredentialSet(UUID.randomUUID(), DeviceId.of("10.0.0.1"), "dev-A", true, false));

        MvcResult result = mvc.perform(post("/api/agents/lab-agent/set-credential").contentType(JSON).content(CRED_BODY))
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                // the response carries the outcome but NOT the secret
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"credRef\":\"dev-A\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sup3r-secret"))));

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(engine).execute(cmd.capture(), any());
        Command.SetCredential sc = (Command.SetCredential) cmd.getValue();
        // the secret was sealed to the agent's public key (env1:) and the plaintext never appears in the command
        org.junit.jupiter.api.Assertions.assertTrue(sc.sealedSecret().startsWith("env1:"), sc.sealedSecret());
        org.junit.jupiter.api.Assertions.assertFalse(sc.sealedSecret().contains("sup3r-secret"));
    }

    @Test
    void setCredentialPinsTheCredRefOnTheDeviceForFutureOps() throws Exception {
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any()))
                .thenReturn(new Result.CredentialSet(UUID.randomUUID(), DeviceId.of("10.0.0.1"), "dev-A", true, false));

        MvcResult result = mvc.perform(post("/api/agents/lab-agent/set-credential").contentType(JSON).content(CRED_BODY))
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        verify(fleet).setCredRef("acme", "dev-A", "dev-A");
    }
}
