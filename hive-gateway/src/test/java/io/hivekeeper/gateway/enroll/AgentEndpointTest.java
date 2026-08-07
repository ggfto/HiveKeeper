package io.hivekeeper.gateway.enroll;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent hostname is read off the gateway's own server certificate, which is what makes it trustworthy:
 * an agent's TLS handshake verifies the name it dialed against that SAN, so any other value is one the fleet
 * could not connect to anyway. These pin that reading — and the honest {@code null} when there is nothing to
 * read, since a guessed hostname is worse than an empty field the operator fills in.
 */
class AgentEndpointTest {

    @Test
    void readsTheFirstDnsNameFromTheCertificate() throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2, "agents.gf2.in")));

        assertEquals("agents.gf2.in", AgentEndpoint.sanHostname(cert));
    }

    @Test
    void skipsNonHostnameSans() throws Exception {
        // Type 7 is an IP address. An agent is configured with a hostname (the certificate is issued for one),
        // so an IP here is not a usable answer and must not be offered as if it were.
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(7, "192.168.68.104"), List.of(2, "agents.gf2.in")));

        assertEquals("agents.gf2.in", AgentEndpoint.sanHostname(cert));
    }

    @Test
    void aCertificateWithNoSansYieldsNothing() throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectAlternativeNames()).thenReturn(null);

        assertNull(AgentEndpoint.sanHostname(cert));
    }

    /** An endpoint with a known (or deliberately unknown) host: an explicit domain short-circuits resolution,
     *  and no keystore path means nothing else can supply one. */
    private static AgentEndpoint at(String host) {
        return new AgentEndpoint(host, 9443, "", "changeit", "gateway");
    }

    @Test
    void buildsTheTwoUrlsAnAgentIsConfiguredWith() {
        AgentEndpoint endpoint = at("agents.gf2.in");

        assertEquals("wss://agents.gf2.in:9443/agent", endpoint.gatewayUrl());
        assertEquals("https://agents.gf2.in:9443", endpoint.enrollmentUrl());
    }

    @Test
    void anUnknownHostYieldsNoUrlsRatherThanBrokenOnes() {
        AgentEndpoint endpoint = at(null);

        assertNull(endpoint.host());
        assertNull(endpoint.gatewayUrl());
        assertNull(endpoint.enrollmentUrl());
    }

    @Test
    void anExplicitDomainWinsOverTheCertificate() {
        // The escape hatch: a deployment whose public name is a CNAME onto the certificate's name.
        assertEquals("agents.override.example", at("agents.override.example").host());
    }

    @Test
    void noDomainAndNoKeystoreIsUnknown() {
        assertNull(at("").host());
    }

    @Test
    void anUnreadableKeystoreDegradesInsteadOfFailingStartup() {
        // Losing the prefill is a cosmetic loss; refusing to start over it would take the whole control plane
        // down for one form field.
        AgentEndpoint endpoint = new AgentEndpoint("", 9443, "/nonexistent/gateway.p12", "changeit", "gateway");

        assertNull(endpoint.host());
    }
}
