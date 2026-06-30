package io.hivekeeper.gateway.alerts;

import io.hivekeeper.gateway.alerts.AlertService.Channel;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the notifier's severity-floor gating and per-channel dispatch + isolation. */
class AlertNotifierTest {

    private final List<String> posted = new ArrayList<>();
    private final List<String> emailed = new ArrayList<>();
    private final WebhookSender webhook = (url, body) -> posted.add(url + " " + body);
    private final EmailSender email = new EmailSender() {
        public boolean isConfigured() {
            return true;
        }

        public void send(String to, String subject, String body) {
            emailed.add(to + " | " + subject);
        }
    };
    private final AlertNotifier notifier = new AlertNotifier(webhook, email);

    private static Channel ch(String type, String target, String minSeverity, boolean enabled) {
        return new Channel("c-" + target, type, target, minSeverity, enabled, Instant.EPOCH);
    }

    private static AlertEvent event(String severity) {
        return new AlertEvent("firing", "acme", "lab-agent", "d1", "Lobby AP", "10.0.0.1", "high-clients",
                severity, "40 clients", Instant.EPOCH);
    }

    @Test
    void deliversToWebhookAndEmailChannelsThatPassTheSeverityFloor() {
        notifier.notify(List.of(ch("webhook", "https://hook", "warning", true),
                ch("email", "ops@acme.test", "warning", true)), event("warning"));
        assertEquals(1, posted.size());
        assertTrue(posted.get(0).contains("https://hook"));
        assertTrue(posted.get(0).contains("high-clients"));
        assertEquals(List.of("ops@acme.test | [HiveKeeper] WARNING: Lobby AP — high-clients"), emailed);
    }

    @Test
    void dropsAlertsBelowAChannelsSeverityFloor() {
        // an info alert must NOT reach a warning-floor channel; a critical alert must.
        notifier.notify(List.of(ch("webhook", "https://hook", "warning", true)), event("info"));
        assertTrue(posted.isEmpty());
        notifier.notify(List.of(ch("webhook", "https://hook", "warning", true)), event("critical"));
        assertEquals(1, posted.size());
    }

    @Test
    void skipsDisabledChannels() {
        notifier.notify(List.of(ch("webhook", "https://hook", "info", false)), event("critical"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void oneFailingChannelDoesNotBlockOthers() {
        WebhookSender boom = (url, body) -> {
            throw new RuntimeException("connection refused");
        };
        AlertNotifier n = new AlertNotifier(boom, email);
        n.notify(List.of(ch("webhook", "https://dead", "info", true), ch("email", "ops@acme.test", "info", true)),
                event("warning"));
        assertEquals(1, emailed.size());   // email still delivered despite the webhook throwing
    }

    @Test
    void severityFloorHelper() {
        assertTrue(AlertNotifier.passesSeverityFloor("critical", "warning"));
        assertTrue(AlertNotifier.passesSeverityFloor("warning", "warning"));
        assertTrue(!AlertNotifier.passesSeverityFloor("info", "warning"));
    }
}
