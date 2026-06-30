package io.hivekeeper.gateway.alerts;

/** Sends an alert email. An interface so the poller is unit-testable without a real SMTP server. */
public interface EmailSender {

    /** True if email delivery is configured (an SMTP host is set); when false the notifier skips email channels. */
    boolean isConfigured();

    void send(String to, String subject, String body);
}
