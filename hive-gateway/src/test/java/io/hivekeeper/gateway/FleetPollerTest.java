package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.Station;
import io.hivekeeper.gateway.alerts.AlertEvent;
import io.hivekeeper.gateway.alerts.AlertNotifier;
import io.hivekeeper.gateway.alerts.InMemoryAlertService;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the poller's evaluate + dedup + resolution diff against the firing state. Uses the real in-memory alert
 *  store (so dedup is genuine) and mocks the fleet/agent/notifier. */
class FleetPollerTest {

    private final TenantStore tenants = mock(TenantStore.class);
    private final FleetService fleet = mock(FleetService.class);
    private final AgentRegistry registry = mock(AgentRegistry.class);
    private final AlertNotifier notifier = mock(AlertNotifier.class);
    private final InMemoryAlertService alerts = new InMemoryAlertService();
    private FleetPoller poller;

    private static final FleetService.Device DEV = new FleetService.Device(
            "d1", "site-1", "lab-agent", "SER-A", "AP230", "Lobby AP", "10.0.0.1", "cred-x", List.of());

    @BeforeEach
    void setup() {
        poller = new FleetPoller(tenants, fleet, registry, alerts, notifier, new SitePrimary(registry, tenants));
        // a tenant with one enabled webhook channel and the poller on
        alerts.addChannel("acme", "webhook", "https://hook", "warning");
        when(fleet.devicesFor("acme", null, null)).thenReturn(List.of(DEV));
    }

    private static Result.Inventory inventoryWith(int stations) {
        Device d = Device.builder().id(DeviceId.of("10.0.0.1")).model("AP230")
                .stations(Collections.nCopies(stations, new Station("aa", "1.1.1.1", "h", "ssid", "os", -50)))
                .radios(List.of()).build();
        return new Result.Inventory(UUID.randomUUID(), DeviceId.of("10.0.0.1"), d);
    }

    @Test
    void deliversAgentOfflineWhenTheAgentIsNotConnected() {
        when(registry.agentIds("acme")).thenReturn(Set.of());   // lab-agent not connected
        poller.scanTenant("acme");

        ArgumentCaptor<AlertEvent> ev = ArgumentCaptor.forClass(AlertEvent.class);
        verify(notifier).notify(any(), ev.capture());
        assertEquals("agent-offline", ev.getValue().alertId());
        assertEquals("firing", ev.getValue().state());
        assertEquals("critical", ev.getValue().severity());
        // it is now tracked as firing
        assertEquals(1, alerts.firing("acme").size());
    }

    @Test
    void dedupsSoASteadyAlertIsNotReDeliveredEveryScan() {
        when(registry.agentIds("acme")).thenReturn(Set.of());
        poller.scanTenant("acme");   // firing once
        poller.scanTenant("acme");   // still firing — must NOT notify again
        verify(notifier, org.mockito.Mockito.times(1)).notify(any(), any());
    }

    @Test
    void deliversAResolutionWhenAnAlertClears() {
        when(registry.agentIds("acme")).thenReturn(Set.of());
        poller.scanTenant("acme");   // agent-offline fires

        // next scan: agent back + a healthy inventory -> the offline alert resolves
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.agentIds("acme")).thenReturn(Set.of("lab-agent"));
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(Command.class), any())).thenReturn(inventoryWith(2));
        poller.scanTenant("acme");

        ArgumentCaptor<AlertEvent> ev = ArgumentCaptor.forClass(AlertEvent.class);
        verify(notifier, org.mockito.Mockito.atLeast(2)).notify(any(), ev.capture());
        AlertEvent last = ev.getValue();
        assertEquals("agent-offline", last.alertId());
        assertEquals("resolved", last.state());
        assertTrue(alerts.firing("acme").isEmpty());
    }

    @Test
    void firesHighClientsFromAnInventoryOverTheThreshold() {
        RemoteEngine engine = mock(RemoteEngine.class);
        when(registry.agentIds("acme")).thenReturn(Set.of("lab-agent"));
        when(registry.engine("acme", "lab-agent")).thenReturn(Optional.of(engine));
        when(engine.execute(any(Command.class), any())).thenReturn(inventoryWith(31));   // > default 30
        poller.scanTenant("acme");

        ArgumentCaptor<AlertEvent> ev = ArgumentCaptor.forClass(AlertEvent.class);
        verify(notifier).notify(any(), ev.capture());
        assertEquals("high-clients", ev.getValue().alertId());
    }

    @Test
    void skipsEntirelyWhenThereAreNoEnabledChannels() {
        InMemoryAlertService empty = new InMemoryAlertService();   // no channels
        FleetPoller p = new FleetPoller(tenants, fleet, registry, empty, notifier, new SitePrimary(registry, tenants));
        p.scanTenant("acme");
        verify(fleet, never()).devicesFor(any(), any(), any());   // never touched the fleet
        verify(notifier, never()).notify(any(), any());
    }
}
