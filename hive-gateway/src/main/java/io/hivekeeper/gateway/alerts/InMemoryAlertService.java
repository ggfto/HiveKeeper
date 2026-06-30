package io.hivekeeper.gateway.alerts;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dev/demo alert store: process-local, tenant-scoped maps so the {@code !postgres} stack can run the
 * Notifications UI (channels + thresholds) without a database. Mirrors {@link PostgresAlertService}'s
 * behaviour (per-tenant isolation, a unique channel per tenant/type/target) but holds nothing across a
 * restart. The background poller is a {@code postgres}-profile feature, so the firing-state methods here exist
 * only for parity/tests. Synchronized: mutations do compound read-modify-write.
 */
@Service
@Profile("!postgres")
public class InMemoryAlertService implements AlertService {

    private final Map<String, Settings> settings = new LinkedHashMap<>();
    private final Map<String, Map<String, Channel>> channels = new LinkedHashMap<>();
    private final Map<String, Map<String, FiringAlert>> firing = new LinkedHashMap<>();

    @Override
    public synchronized Settings settings(String tenantId) {
        return settings.getOrDefault(tenantId, Settings.DEFAULT);
    }

    @Override
    public synchronized void saveSettings(String tenantId, int maxStations, boolean pollEnabled) {
        settings.put(tenantId, new Settings(Math.max(1, maxStations), pollEnabled));
    }

    @Override
    public synchronized List<Channel> channels(String tenantId) {
        return new ArrayList<>(channels.getOrDefault(tenantId, Map.of()).values());
    }

    @Override
    public synchronized String addChannel(String tenantId, String type, String target, String minSeverity) {
        Map<String, Channel> byId = channels.computeIfAbsent(tenantId, k -> new LinkedHashMap<>());
        boolean clash = byId.values().stream()
                .anyMatch(c -> c.type().equals(type) && c.target().equals(target));
        if (clash) {
            throw new DuplicateKeyException("a " + type + " channel for '" + target + "' already exists");
        }
        String id = "ch-" + UUID.randomUUID();
        byId.put(id, new Channel(id, type, target, minSeverity, true, Instant.now()));
        return id;
    }

    @Override
    public synchronized void setChannelEnabled(String tenantId, String channelId, boolean enabled) {
        Map<String, Channel> byId = channels.getOrDefault(tenantId, Map.of());
        Channel c = byId.get(channelId);
        if (c != null) {
            byId.put(channelId, new Channel(c.id(), c.type(), c.target(), c.minSeverity(), enabled, c.createdAt()));
        }
    }

    @Override
    public synchronized void removeChannel(String tenantId, String channelId) {
        channels.getOrDefault(tenantId, Map.of()).remove(channelId);
    }

    @Override
    public synchronized List<FiringAlert> firing(String tenantId) {
        return new ArrayList<>(firing.getOrDefault(tenantId, Map.of()).values());
    }

    @Override
    public synchronized void markFiring(String tenantId, FiringAlert alert) {
        Map<String, FiringAlert> byKey = firing.computeIfAbsent(tenantId, k -> new LinkedHashMap<>());
        String key = alert.deviceId() + "/" + alert.alertId();
        FiringAlert existing = byKey.get(key);
        if (existing == null) {
            byKey.put(key, alert);
        } else {
            byKey.put(key, new FiringAlert(existing.deviceId(), alert.agentId(), existing.alertId(),
                    alert.severity(), alert.message(), existing.firstSeen(), alert.lastSeen()));
        }
    }

    @Override
    public synchronized void resolveFiring(String tenantId, String deviceId, String alertId) {
        firing.getOrDefault(tenantId, Map.of()).remove(deviceId + "/" + alertId);
    }
}
