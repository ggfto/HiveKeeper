package io.hivekeeper.gateway;

import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.backup.BackupDestinationService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.alerts.AlertService;
import io.hivekeeper.gateway.tenant.TenantStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pins alert endpoints: reads need viewer on the org, channel/threshold mutations need admin, and the channel
 *  create validates its inputs. */
@WebMvcTest(GatewayController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
class GatewayControllerAlertsTest {

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
    private AlertService alerts;
    @MockitoBean
    private BackupDestinationService backupDestinations;
    @MockitoBean
    private BackupDestinationProvisioner backupProvisioner;
    @MockitoBean
    private SitePrimary sitePrimary;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() {
        when(guard.authenticate()).thenReturn(principal);
        when(alerts.settings("acme")).thenReturn(AlertService.Settings.DEFAULT);
        when(alerts.channels("acme")).thenReturn(List.of());
        when(alerts.firing("acme")).thenReturn(List.of());
    }

    @Test
    void readingSettingsChannelsAndFiringNeedsViewerOnTheOrg() throws Exception {
        mvc.perform(get("/api/alerts/settings"));
        mvc.perform(get("/api/alerts/channels"));
        mvc.perform(get("/api/alerts/firing"));
        verify(guard, org.mockito.Mockito.times(3)).require(eq(principal), eq(Role.VIEWER), eq(ResourceScope.org()));
    }

    @Test
    void mutatingSettingsNeedsAdminOnTheOrg() throws Exception {
        mvc.perform(post("/api/alerts/settings").contentType(JSON).content("{\"maxStations\":50,\"pollEnabled\":true}"));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void addingAChannelNeedsAdminOnTheOrg() throws Exception {
        when(alerts.addChannel(eq("acme"), eq("webhook"), eq("https://hook"), eq("warning"))).thenReturn("ch-1");
        when(alerts.channels("acme")).thenReturn(List.of(new AlertService.Channel(
                "ch-1", "webhook", "https://hook", "warning", true, java.time.Instant.EPOCH)));
        mvc.perform(post("/api/alerts/channels").contentType(JSON)
                        .content("{\"type\":\"webhook\",\"target\":\"https://hook\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ch-1")));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void removingAndTogglingAChannelNeedAdmin() throws Exception {
        mvc.perform(delete("/api/alerts/channels/ch-1"));
        mvc.perform(post("/api/alerts/channels/ch-1").contentType(JSON).content("{\"enabled\":false}"));
        verify(guard, org.mockito.Mockito.times(2)).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void addChannelRejectsAnUnknownType() throws Exception {
        mvc.perform(post("/api/alerts/channels").contentType(JSON)
                        .content("{\"type\":\"sms\",\"target\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("type must be webhook or email")));
    }

    @Test
    void addChannelRejectsAnInvalidSeverity() throws Exception {
        mvc.perform(post("/api/alerts/channels").contentType(JSON)
                        .content("{\"type\":\"email\",\"target\":\"a@b.test\",\"minSeverity\":\"meh\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("minSeverity")));
    }
}
