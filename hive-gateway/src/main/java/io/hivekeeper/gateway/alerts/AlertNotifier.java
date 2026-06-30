package io.hivekeeper.gateway.alerts;

import io.hivekeeper.core.alerts.AlertRules;
import io.hivekeeper.gateway.alerts.AlertService.Channel;
import io.hivekeeper.wire.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Delivers an {@link AlertEvent} to a tenant's enabled channels. A channel only receives alerts at or above its
 * {@code minSeverity} floor (critical &lt; warning &lt; info, so a 'warning' floor passes warning + critical, drops
 * info). One failing channel never blocks the others. Carries no secrets — the event has only fleet metadata.
 */
@Component
@Slf4j
public class AlertNotifier {

    private final WebhookSender webhook;
    private final EmailSender email;
    private final JsonCodec codec = new JsonCodec();

    public AlertNotifier(WebhookSender webhook, EmailSender email) {
        this.webhook = webhook;
        this.email = email;
    }

    public void notify(List<Channel> channels, AlertEvent event) {
        for (Channel ch : channels) {
            if (!ch.enabled() || !passesSeverityFloor(event.severity(), ch.minSeverity())) {
                continue;
            }
            try {
                switch (ch.type()) {
                    case "webhook" -> webhook.post(ch.target(), codec.toJson(event));
                    case "email" -> email.send(ch.target(), subject(event), body(event));
                    default -> log.warn("unknown alert channel type '{}' (channel {})", ch.type(), ch.id());
                }
            } catch (Exception e) {
                log.warn("alert delivery to {} channel '{}' failed: {}", ch.type(), ch.target(), e.getMessage());
            }
        }
    }

    /** True if the alert is at least as severe as the channel's floor. */
    static boolean passesSeverityFloor(String alertSeverity, String channelFloor) {
        return AlertRules.severityRank(alertSeverity) <= AlertRules.severityRank(channelFloor);
    }

    private static String subject(AlertEvent e) {
        String verb = "resolved".equals(e.state()) ? "RESOLVED" : e.severity().toUpperCase(java.util.Locale.ROOT);
        return "[HiveKeeper] " + verb + ": " + label(e) + " — " + e.alertId();
    }

    private static String body(AlertEvent e) {
        return ("resolved".equals(e.state()) ? "Alert resolved.\n\n" : "Alert firing.\n\n")
                + "Device:   " + label(e) + (e.host() == null ? "" : " (" + e.host() + ")") + "\n"
                + "Severity: " + e.severity() + "\n"
                + "Alert:    " + e.alertId() + "\n"
                + "Detail:   " + (e.message() == null ? "" : e.message()) + "\n"
                + "When:     " + e.at() + "\n";
    }

    private static String label(AlertEvent e) {
        return e.deviceLabel() != null && !e.deviceLabel().isBlank() ? e.deviceLabel() : e.deviceId();
    }
}
