package io.hivekeeper.gateway.alerts;

/** Posts an alert as JSON to a webhook URL. An interface so the poller is unit-testable without real HTTP. */
public interface WebhookSender {

    void post(String url, String jsonBody);
}
