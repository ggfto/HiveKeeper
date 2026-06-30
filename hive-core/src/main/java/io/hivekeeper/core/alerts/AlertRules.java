package io.hivekeeper.core.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Threshold-based health alerts for an AP, evaluated from a monitoring snapshot. A faithful Java port of the
 * web {@code alerts.js} so the server-side fleet poller and the in-console Alerts view fire the same rules. Pure
 * and side-effect-free.
 *
 * <p>{@code online} is whether the device's agent is connected; the snapshot is what an inventory read yields
 * (client/station count, radios, and — when a read provides it — the CAPWAP/cloud-managed flag). Missing data
 * simply means a rule does not fire. The current Java inventory does not capture channel width or CAPWAP state,
 * so those rules stay dormant until the inventory is enriched; agent-offline, high-clients, and the channel /
 * power radio advisories fire from a plain inventory.
 */
public final class AlertRules {

    /** One fired alert. */
    public record Alert(String id, String severity, String message) {
    }

    /** Operator thresholds. Defaults: {@code maxStations = 30}. */
    public record Thresholds(int maxStations) {
        public static final Thresholds DEFAULT = new Thresholds(30);
    }

    /** A radio as the rules read it (width nullable — the inventory may not carry it). */
    public record RadioView(String name, String channel, String power, String width) {
    }

    /** The monitoring snapshot the rules consume. {@code cloudManaged} nullable (unknown when a read omits it). */
    public record Snapshot(Boolean cloudManaged, Integer stationCount, List<RadioView> radios) {
        public Snapshot {
            radios = radios == null ? List.of() : List.copyOf(radios);
        }
    }

    private static final Map<String, Integer> SEVERITY_RANK = Map.of("critical", 0, "warning", 1, "info", 2);

    private AlertRules() {
    }

    /** Sort rank for a severity (lower = worse); unknown severities rank last. */
    public static int severityRank(String severity) {
        return SEVERITY_RANK.getOrDefault(severity, 99);
    }

    /** The worst (most severe) severity among the alerts, or null when there are none. */
    public static String worstSeverity(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return null;
        }
        String worst = "info";
        for (Alert a : alerts) {
            if (severityRank(a.severity()) < severityRank(worst)) {
                worst = a.severity();
            }
        }
        return worst;
    }

    public static List<Alert> evaluate(boolean online, Snapshot snapshot, Thresholds thresholds) {
        Thresholds t = thresholds == null ? Thresholds.DEFAULT : thresholds;
        // An offline agent makes the AP unreachable; nothing else is knowable, so this is the only alert.
        if (!online) {
            return List.of(new Alert("agent-offline", "critical", "Agent offline — the AP is unreachable."));
        }
        List<Alert> out = new ArrayList<>();
        if (snapshot == null) {
            return out;
        }
        // HiveKeeper's signature: a standalone AP should NOT be phoning home. CAPWAP still up = not standalone.
        if (Boolean.TRUE.equals(snapshot.cloudManaged())) {
            out.add(new Alert("cloud-managed", "warning", "Still cloud-managed (CAPWAP up) — not standalone."));
        }
        Integer stations = snapshot.stationCount();
        if (stations != null && stations > t.maxStations()) {
            out.add(new Alert("high-clients", "warning",
                    stations + " clients (> " + t.maxStations() + ") — high load on this AP."));
        }
        for (RadioView r : snapshot.radios()) {
            String name = r.name() == null ? "" : r.name();
            List<RadioAdvisories.Advisory> warnings = RadioAdvisories
                    .advise(name.toLowerCase(Locale.ROOT), r.channel(), r.power(), r.width())
                    .stream().filter(a -> "warning".equals(a.level())).toList();
            if (!warnings.isEmpty()) {
                String codes = String.join(", ", warnings.stream().map(RadioAdvisories.Advisory::code).toList());
                out.add(new Alert("radio-" + name.toLowerCase(Locale.ROOT), "info",
                        "Radio " + name + ": " + codes + "."));
            }
        }
        return out;
    }
}
