package io.hivekeeper.gateway.alerts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Default {@link WebhookSender}: POSTs the JSON body with bounded connect/read timeouts. */
@Component
public class RestTemplateWebhookSender implements WebhookSender {

    private final RestClient http;

    public RestTemplateWebhookSender(@Value("${hivekeeper.alert.webhook.timeout-ms:5000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void post(String url, String jsonBody) {
        http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(jsonBody).retrieve().toBodilessEntity();
    }
}
