package org.springframework.samples.petclinic.integration.publish;

import java.net.URI;
import java.net.http.HttpClient;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * POSTs one event to the configured webhook. Never throws: every outcome becomes a
 * {@link PublishResult}. Error classification is refined in T3.
 */
@Component
public class HttpWebhookPublisher implements EventPublisher {

    public static final MediaType CLOUDEVENTS_JSON = MediaType.parseMediaType("application/cloudevents+json");
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient client;
    private final URI endpoint;
    private final String authToken;

    public HttpWebhookPublisher(IntegrationProperties properties) {
        IntegrationProperties.Publisher settings = properties.publisher();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(settings.readTimeout());
        this.client = RestClient.builder().requestFactory(requestFactory).build();
        this.endpoint = settings.endpoint();
        this.authToken = settings.authToken();
    }

    @Override
    public PublishResult publish(OutboxEvent event) {
        try {
            ResponseEntity<Void> response = client.post()
                .uri(endpoint)
                .contentType(CLOUDEVENTS_JSON)
                .header(IDEMPOTENCY_KEY, event.eventId())
                .headers(headers -> {
                    if (StringUtils.hasText(authToken)) {
                        headers.setBearerAuth(authToken);
                    }
                })
                .body(CloudEventEnvelope.toJson(event))
                .retrieve()
                .onStatus(status -> true, (request, httpResponse) -> {
                    // every status is handled below instead of being thrown
                })
                .toBodilessEntity();
            if (response.getStatusCode().is2xxSuccessful()) {
                return PublishResult.success();
            }
            return PublishResult.retryable("HTTP " + response.getStatusCode().value());
        } catch (RestClientException e) {
            return PublishResult.retryable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
