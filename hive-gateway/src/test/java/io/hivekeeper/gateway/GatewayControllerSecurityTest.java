package io.hivekeeper.gateway;

import io.hivekeeper.gateway.access.AccessException;
import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
            "/api/agents/lab-agent/reboot"})
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
}
