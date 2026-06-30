package io.hivekeeper.gateway.alerts;

import java.time.Instant;
import java.util.List;

/**
 * Persistence for fleet alerting: notification channels, per-tenant thresholds, and the firing-alert state the
 * background poller diffs against. Two implementations, selected by profile: {@link PostgresAlertService}
 * (shared-schema Postgres with row-level security) and {@link InMemoryAlertService} (the no-database dev/demo
 * stack). Both enforce tenant isolation; authorization is layered on top in {@code GatewayController}.
 */
public interface AlertService {

    /** A delivery target. {@code type} is {@code webhook}|{@code email}; {@code target} the URL/address;
     *  {@code minSeverity} the least-severe alert it receives (critical|warning|info). */
    record Channel(String id, String type, String target, String minSeverity, boolean enabled,
                   Instant createdAt) {
    }

    /** Per-tenant poller settings. */
    record Settings(int maxStations, boolean pollEnabled) {
        public static final Settings DEFAULT = new Settings(30, true);
    }

    /** A currently-firing alert (the poller's dedup state). */
    record FiringAlert(String deviceId, String agentId, String alertId, String severity, String message,
                       Instant firstSeen, Instant lastSeen) {
    }

    // -- settings ---------------------------------------------------------------
    Settings settings(String tenantId);

    void saveSettings(String tenantId, int maxStations, boolean pollEnabled);

    // -- channels ---------------------------------------------------------------
    List<Channel> channels(String tenantId);

    /** Returns the new channel id. */
    String addChannel(String tenantId, String type, String target, String minSeverity);

    void setChannelEnabled(String tenantId, String channelId, boolean enabled);

    void removeChannel(String tenantId, String channelId);

    // -- firing-alert state (poller dedup) --------------------------------------
    List<FiringAlert> firing(String tenantId);

    /** Insert a newly-firing alert, or refresh {@code last_seen} if it is already firing. */
    void markFiring(String tenantId, FiringAlert alert);

    void resolveFiring(String tenantId, String deviceId, String alertId);
}
