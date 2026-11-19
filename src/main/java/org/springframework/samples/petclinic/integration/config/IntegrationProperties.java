package org.springframework.samples.petclinic.integration.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code petclinic.integration.*} (NFR-14). Events are always recorded; the relay
 * runs on a schedule only when {@code publisher.enabled=true}.
 */
@ConfigurationProperties("petclinic.integration")
public record IntegrationProperties(@DefaultValue Publisher publisher, @DefaultValue Relay relay,
                                    @DefaultValue Retry retry) {

    public record Publisher(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8089/events") URI endpoint,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        String authToken) {
    }

    public record Relay(
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60s") Duration lease) {
    }

    /** Backoff for retryable failures (ADR-004): random(0, min(maxDelay, initialDelay * multiplier^(n-1))). */
    public record Retry(
        @DefaultValue("2s") Duration initialDelay,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("10m") Duration maxDelay,
        @DefaultValue("15") int maxAttempts) {
    }
}
