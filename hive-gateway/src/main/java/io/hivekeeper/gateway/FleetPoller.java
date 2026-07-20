package io.hivekeeper.gateway;

import io.hivekeeper.core.alerts.AlertRules;
import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.Radio;
import io.hivekeeper.gateway.alerts.AlertEvent;
import io.hivekeeper.gateway.alerts.AlertNotifier;
import io.hivekeeper.gateway.alerts.AlertService;
import io.hivekeeper.gateway.alerts.AlertService.Channel;
import io.hivekeeper.gateway.alerts.AlertService.FiringAlert;
import io.hivekeeper.gateway.fleet.FleetService;
import io.hivekeeper.gateway.tenant.Tenant;
import io.hivekeeper.gateway.tenant.TenantStore;
import io.hivekeeper.protocol.RemoteEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The background fleet poller (PPSK-independent): on a fixed schedule it scans every tenant's devices, evaluates
 * the {@link AlertRules}, and delivers alerts to the tenant's channels. State in {@code fleet_alert} dedups
 * delivery — a rule is delivered when it first fires and again when it resolves, never on every poll. A
 * {@code postgres}-profile feature (needs persistence + cross-tenant enumeration); the in-console Alerts page
 * remains the on-demand view for the no-database stack.
 *
 * <p>Reuses the same per-device path as bulk inventory: reach each device through ITS agent with ITS credential
 * reference, never the cloud. Runs on a scheduler thread (no MVC timeout); one tenant's failure never aborts the
 * rest. A tenant with the poll disabled or no enabled channels is skipped before any agent is touched.
 */
@Component
@Profile("postgres")
@Slf4j
public class FleetPoller {

    private final TenantStore tenants;
    private final FleetService fleet;
    private final AgentRegistry registry;
    private final AlertService alerts;
    private final AlertNotifier notifier;

    private final SitePrimary sitePrimary;

    public FleetPoller(TenantStore tenants, FleetService fleet, AgentRegistry registry, AlertService alerts,
                       AlertNotifier notifier, SitePrimary sitePrimary) {
        this.tenants = tenants;
        this.fleet = fleet;
        this.registry = registry;
        this.alerts = alerts;
        this.notifier = notifier;
        this.sitePrimary = sitePrimary;
    }

    @Scheduled(fixedDelayString = "${hivekeeper.alert.poll-interval-ms:300000}",
            initialDelayString = "${hivekeeper.alert.poll-initial-delay-ms:60000}")
    public void scanAll() {
        for (Tenant tenant : tenants.listAllTenants()) {
            try {
                scanTenant(tenant.tenantId());
            } catch (Exception e) {
                log.warn("alert scan for tenant '{}' failed: {}", tenant.tenantId(), e.getMessage());
            }
        }
    }

    /** Scan one tenant: evaluate every device, then diff against the firing state to deliver onsets/resolutions.
     *  Public for tests; the scheduler calls {@link #scanAll()}. */
    public void scanTenant(String tenantId) {
        AlertService.Settings settings = alerts.settings(tenantId);
        if (!settings.pollEnabled()) {
            return;
        }
        List<Channel> channels = alerts.channels(tenantId).stream().filter(Channel::enabled).toList();
        if (channels.isEmpty()) {
            return;   // nothing to deliver to — don't touch any agent
        }
        AlertRules.Thresholds thresholds = new AlertRules.Thresholds(settings.maxStations());
        List<FleetService.Device> devices = fleet.devicesFor(tenantId, null, null);
        Map<String, FleetService.Device> byId = new LinkedHashMap<>();
        for (FleetService.Device d : devices) {
            byId.put(d.deviceId(), d);
        }
        Set<String> connected = registry.agentIds(tenantId);

        Map<String, FiringAlert> firing = new LinkedHashMap<>();
        for (FiringAlert fa : alerts.firing(tenantId)) {
            firing.put(fa.deviceId() + "/" + fa.alertId(), fa);
        }

        Set<String> seen = new HashSet<>();
        for (FleetService.Device d : devices) {
            if (d.agentId() == null) {
                continue;   // unmanaged device — not pollable
            }
            for (AlertRules.Alert a : evaluateDevice(tenantId, d, connected, thresholds)) {
                String key = d.deviceId() + "/" + a.id();
                seen.add(key);
                boolean isNew = !firing.containsKey(key);
                alerts.markFiring(tenantId, new FiringAlert(d.deviceId(), d.agentId(), a.id(), a.severity(),
                        a.message(), null, null));
                if (isNew) {
                    notifier.notify(channels, event("firing", tenantId, d, a.id(), a.severity(), a.message()));
                }
            }
        }

        // Anything that was firing but is no longer present has resolved.
        for (FiringAlert fa : firing.values()) {
            if (seen.contains(fa.deviceId() + "/" + fa.alertId())) {
                continue;
            }
            alerts.resolveFiring(tenantId, fa.deviceId(), fa.alertId());
            FleetService.Device d = byId.get(fa.deviceId());
            String label = d != null ? d.label() : null;
            String host = d != null ? d.mgmtIp() : null;
            notifier.notify(channels, new AlertEvent("resolved", tenantId, fa.agentId(), fa.deviceId(), label,
                    host, fa.alertId(), fa.severity(), fa.message(), Instant.now()));
        }
    }

    private List<AlertRules.Alert> evaluateDevice(String tenantId, FleetService.Device d, Set<String> connected,
                                                  AlertRules.Thresholds thresholds) {
        // Poll through the site's current primary, not the pinned agent: with an active/standby pair, the
        // device is still reachable via the standby when the primary is down, so it must not alert offline.
        String agent = sitePrimary.servingAgent(tenantId, d.siteId(), d.agentId());
        if (agent == null || !connected.contains(agent)) {
            return AlertRules.evaluate(false, null, thresholds);   // no agent on the site can reach it
        }
        Optional<RemoteEngine> engine = registry.engine(tenantId, agent);
        if (engine.isEmpty() || d.mgmtIp() == null) {
            return AlertRules.evaluate(false, null, thresholds);
        }
        try {
            Result r = engine.get().execute(
                    Command.Inventory.of(DeviceRef.ssh(d.mgmtIp(), 22, d.credRef())), EventSink.NOOP);
            if (r instanceof Result.Inventory inv) {
                return AlertRules.evaluate(true, toSnapshot(inv.device()), thresholds);
            }
            return List.of();
        } catch (Exception e) {
            // The agent is connected but the AP read failed — a real, deliverable condition (poller-level rule).
            String detail = e.getMessage() == null ? "" : e.getMessage();
            return List.of(new AlertRules.Alert("inventory-failed", "warning",
                    "Inventory read failed — the AP may be unreachable behind a connected agent. " + detail));
        }
    }

    /** Maps a hive-core inventory {@link Device} to the rules' snapshot. The Java inventory does not capture
     *  channel width or CAPWAP/cloud state, so those rule inputs are left unset (the corresponding rules stay
     *  dormant) — matching the in-console scan, which reads the same inventory. */
    static AlertRules.Snapshot toSnapshot(Device device) {
        List<AlertRules.RadioView> radios = device.radios().stream()
                .map(FleetPoller::toRadioView).toList();
        return new AlertRules.Snapshot(null, device.stations().size(), radios);
    }

    private static AlertRules.RadioView toRadioView(Radio r) {
        String channel = r.channel() == null ? null : String.valueOf(r.channel());
        return new AlertRules.RadioView(r.name(), channel, r.power(), null);
    }

    private static AlertEvent event(String state, String tenantId, FleetService.Device d, String alertId,
                                    String severity, String message) {
        return new AlertEvent(state, tenantId, d.agentId(), d.deviceId(), d.label(), d.mgmtIp(), alertId,
                severity, message, Instant.now());
    }
}
