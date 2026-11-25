package com.pdm.mvp.alert;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WebhookNotifier {
    private final RestClient client;
    private final String url;
    private final boolean enabled;

    public WebhookNotifier(@Value("${pdm.webhook.url:}") String url, @Value("${pdm.webhook.enabled:false}") boolean enabled) {
        this.url = url;
        this.enabled = enabled;
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().requestFactory(requestFactory).build();
    }

    boolean enabled() { return enabled; }

    Optional<String> send(NotificationEvent event, int maxAttempts) {
        if (url.isBlank()) return Optional.of("Webhook URL missing");
        var payload = new LinkedHashMap<>(event.payload());
        payload.put("notificationId", event.id());
        payload.put("eventKind", event.kind());
        payload.put("attempt", event.attempts());
        payload.put("maxAttempts", maxAttempts);
        try {
            return client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "pdm-notification-" + event.id()).body(payload)
                    .exchange((request, response) -> {
                        var status = response.getStatusCode();
                        return status.is2xxSuccessful() ? Optional.<String>empty() : Optional.of("HTTP " + status.value());
                    });
        } catch (IllegalArgumentException failure) {
            return Optional.of("Invalid webhook configuration");
        } catch (RestClientException failure) {
            return Optional.of("Webhook transport failure");
        }
    }
}
