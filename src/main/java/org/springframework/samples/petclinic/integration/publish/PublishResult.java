package org.springframework.samples.petclinic.integration.publish;

import java.time.Duration;

/**
 * Outcome of one publish attempt. {@code retryAfter} is the minimum wait the external system asked
 * for (HTTP {@code Retry-After}), or null.
 */
public record PublishResult(Outcome outcome, String error, Duration retryAfter) {

    public enum Outcome {
        SUCCESS,
        RETRYABLE,
        PERMANENT
    }

    public static PublishResult success() {
        return new PublishResult(Outcome.SUCCESS, null, null);
    }

    public static PublishResult retryable(String error) {
        return new PublishResult(Outcome.RETRYABLE, error, null);
    }

    public static PublishResult retryable(String error, Duration retryAfter) {
        return new PublishResult(Outcome.RETRYABLE, error, retryAfter);
    }

    public static PublishResult permanent(String error) {
        return new PublishResult(Outcome.PERMANENT, error, null);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
