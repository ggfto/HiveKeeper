package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.tenant.AgentEnrollment;
import io.hivekeeper.gateway.tenant.TenantStore;
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
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins that a secret-bearing durable job is sealed to the agent before it is persisted — so the gateway never
 *  holds the SSID passphrase in a form it can read at rest — while a non-secret job is submitted unchanged. */
@WebMvcTest(GatewayController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
class GatewayControllerJobSealTest {

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
    private JobGateway jobGateway;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() throws Exception {
        when(guard.authenticate()).thenReturn(principal);
        when(tenants.enrollmentByAgentId("lab-agent")).thenReturn(Optional.of(new AgentEnrollment("t", "lab-agent", "acme")));
        when(tenants.agentSiteId("lab-agent")).thenReturn(Optional.of("site-1"));
        when(jobGateway.submit(any(), any(), any(), any())).thenReturn("job-1");
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        when(registry.publicKey("acme", "lab-agent")).thenReturn(Optional.of(kpg.generateKeyPair().getPublic()));
    }

    @Test
    void aConfigureSsidJobIsSealedToTheAgentAndLeaksNoPassphrase() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/jobs").contentType(JSON)
                .content("{\"type\":\"configure-ssid\",\"host\":\"10.0.0.1\",\"name\":\"Corp\",\"psk\":\"sup3r-psk\",\"vlan\":30}"));

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(jobGateway).submit(eq("acme"), eq("lab-agent"), eq("configure-ssid"), cmd.capture());
        Command.Sealed sealed = assertInstanceOf(Command.Sealed.class, cmd.getValue());
        assertTrue(sealed.sealedCommand().startsWith("env1:"), sealed.sealedCommand());
        assertFalse(sealed.sealedCommand().contains("sup3r-psk"), "the passphrase must not be in the persisted command");
    }

    @Test
    void aNonSecretJobIsSubmittedUnsealed() throws Exception {
        mvc.perform(post("/api/agents/lab-agent/jobs").contentType(JSON)
                .content("{\"type\":\"inventory\",\"host\":\"10.0.0.1\"}"));

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(jobGateway).submit(eq("acme"), eq("lab-agent"), eq("inventory"), cmd.capture());
        assertInstanceOf(Command.Inventory.class, cmd.getValue());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
    }
}
