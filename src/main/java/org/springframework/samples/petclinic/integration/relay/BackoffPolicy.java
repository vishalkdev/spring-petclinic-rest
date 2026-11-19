package org.springframework.samples.petclinic.integration.relay;

import java.time.Duration;
import java.util.function.DoubleSupplier;

import org.springframework.samples.petclinic.integration.config.IntegrationProperties;

/**
 * Exponential backoff with full jitter (ADR-004). The jitter source returns a value in [0, 1);
 * tests pass a fixed value to make delays predictable.
 */
public class BackoffPolicy {

    private final IntegrationProperties.Retry settings;
    private final DoubleSupplier jitter;

    public BackoffPolicy(IntegrationProperties.Retry settings, DoubleSupplier jitter) {
        this.settings = settings;
        this.jitter = jitter;
    }

    public int maxAttempts() {
        return settings.maxAttempts();
    }

    /**
     * Delay before the next attempt, after {@code failedAttempts} failures (1 = first failure).
     * A {@code retryAfter} from the external system is a lower bound, still capped at the max delay.
     */
    public Duration delayAfter(int failedAttempts, Duration retryAfter) {
        double ceilingMillis = Math.min(
            settings.maxDelay().toMillis(),
            settings.initialDelay().toMillis() * Math.pow(settings.multiplier(), failedAttempts - 1));
        Duration delay = Duration.ofMillis((long) (ceilingMillis * jitter.getAsDouble()));
        if (retryAfter != null) {
            Duration requested = retryAfter.compareTo(settings.maxDelay()) > 0 ? settings.maxDelay() : retryAfter;
            if (requested.compareTo(delay) > 0) {
                delay = requested;
            }
        }
        return delay;
    }
}
