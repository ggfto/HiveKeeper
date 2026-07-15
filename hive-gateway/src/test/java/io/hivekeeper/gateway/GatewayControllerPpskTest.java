package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.ppsk.PpskUserService;
import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the PPSK-user endpoints (Caminho B): list needs viewer, create/rotate/revoke need operator on the
 * agent's site, the generated key is sealed to the agent (env1:) and never leaks plaintext, and the create
 * response returns the one-time PSK while persisting only metadata.
 */
@WebMvcTest(GatewayController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
class GatewayControllerPpskTest {

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
    private PpskUserService ppskUsers;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() {
        when(guard.authenticate()).thenReturn(principal);
        when(tenants.enrollmentByAgentId("lab-agent")).thenReturn(Optional.of(new AgentEnrollment("t", "lab-agent", "acme")));
        when(tenants.agentSiteId("lab-agent")).thenReturn(Optional.of("site-1"));
    }

    private static PpskUserService.PpskUser row(String id, String status) {
        return new PpskUserService.PpskUser(id, "lab-agent", "Corp", "staff", "alice", "pskref-x", 99, 30,
                null, List.of(), status, Instant.parse("2026-06-30T12:00:00Z"), null);
    }

    @Test
    void listRequiresViewerOnTheAgentSite() throws Exception {
        when(ppskUsers.list("acme", "lab-agent")).thenReturn(List.of());
        mvc.perform(get("/api/agents/lab-agent/ppsk-users"));
        verify(guard).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.site("site-1")));
    }

    @Test
    void createRequiresOperatorOnTheAgentSite() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/ppsk-users").contentType(JSON)
                .content("{\"securityObject\":\"Corp\",\"username\":\"alice\"}"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.site("site-1")));
    }

    @Test
    void rotateRequiresOperatorOnTheAgentSite() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/ppsk-users/ppsk-1/rotate"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.site("site-1")));
    }

    @Test
    void revokeRequiresOperatorOnTheAgentSite() throws Exception {
        mvc.perform(delete("/api/agents/lab-agent/ppsk-users/ppsk-1"));
        verify(guard).require(eq(principal), eq(Role.OPERATOR), eq(ResourceScope.site("site-1")));
    }

    @Test
    void createSealsTheGeneratedKeyToTheAgentAndReturnsItOnceWithoutPersistingIt() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair agentKey = kpg.generateKeyPair();
        when(registry.publicKey("acme", "lab-agent")).thenReturn(Optional.of(agentKey.getPublic()));
        RemoteEngine engine = org.mockito.Mockito.mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any())).thenReturn(
                new Result.PpskUserManaged(UUID.randomUUID(), DeviceId.of("ppsk:Corp"), "alice", "Corp", "active"));
        when(ppskUsers.create(eq("acme"), eq("lab-agent"), eq("Corp"), any(), eq("alice"), any(), any(), any(), any(), any()))
                .thenReturn("ppsk-1");
        when(ppskUsers.get("acme", "ppsk-1")).thenReturn(Optional.of(row("ppsk-1", "active")));

        MvcResult result = mvc.perform(post("/api/agents/lab-agent/ppsk-users").contentType(JSON)
                .content("{\"securityObject\":\"Corp\",\"userGroup\":\"staff\",\"username\":\"alice\",\"vlanId\":30}"))
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"psk\":\"")))      // one-time key returned
                .andExpect(content().string(containsString("\"username\":\"alice\"")))
                .andExpect(content().string(not(containsString("pskref-x"))));   // the ref is internal, not echoed as the key

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(engine).execute(cmd.capture(), any());
        Command.ManagePpskUser mp = (Command.ManagePpskUser) cmd.getValue();
        assertTrue(mp.sealedPsk().startsWith("env1:"), mp.sealedPsk());          // sealed to the agent key
        assertTrue("create".equals(mp.action()));
        // metadata persisted; the usable key was sealed, never handed to the service
        verify(ppskUsers).create(eq("acme"), eq("lab-agent"), eq("Corp"), eq("staff"), eq("alice"), any(),
                any(), eq(30), any(), any());
    }

    @Test
    void createWithoutAConnectedAgentIs404() throws Exception {
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.empty());
        MvcResult result = mvc.perform(post("/api/agents/lab-agent/ppsk-users").contentType(JSON)
                .content("{\"securityObject\":\"Corp\",\"username\":\"alice\"}")).andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("agent_not_connected")));
    }

    @Test
    void revokeDispatchesTheRevokeCommandAndMarksTheRowRevoked() throws Exception {
        when(ppskUsers.get("acme", "ppsk-1")).thenReturn(Optional.of(row("ppsk-1", "active")));
        RemoteEngine engine = org.mockito.Mockito.mock(RemoteEngine.class);
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(), any())).thenReturn(
                new Result.PpskUserManaged(UUID.randomUUID(), DeviceId.of("ppsk:Corp"), "alice", "Corp", "revoked"));

        MvcResult result = mvc.perform(delete("/api/agents/lab-agent/ppsk-users/ppsk-1")).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(engine).execute(cmd.capture(), any());
        Command.ManagePpskUser mp = (Command.ManagePpskUser) cmd.getValue();
        assertTrue("revoke".equals(mp.action()));
        assertFalse(mp.action().equals("create"));
        verify(ppskUsers).revoke("acme", "ppsk-1");
    }
}
