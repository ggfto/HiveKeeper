package io.hivekeeper.gateway.alerts;

import java.time.Instant;

/**
 * One alert delivery: the JSON body a webhook receives and the source for an email's subject/body. {@code state}
 * is {@code firing} (onset) or {@code resolved} (cleared). Carries no secrets.
 */
public record AlertEvent(String state, String tenantId, String agentId, String deviceId, String deviceLabel,
                         String host, String alertId, String severity, String message, Instant at) {
}
