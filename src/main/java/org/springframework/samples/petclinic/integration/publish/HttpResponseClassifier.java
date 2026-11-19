package org.springframework.samples.petclinic.integration.publish;

import java.time.Duration;

/**
 * Turns an HTTP response from the external system into a {@link PublishResult} (ADR-004).
 * <ul>
 *   <li>2xx: success</li>
 *   <li>408, 425, 429, 5xx: retryable; {@code Retry-After} in seconds is honoured</li>
 *   <li>everything else (3xx, other 4xx): permanent, because retrying the same request cannot help</li>
 * </ul>
 */
public final class HttpResponseClassifier {

    private static final int MAX_BODY_IN_ERROR = 300;

    private HttpResponseClassifier() {
    }

    public static PublishResult classify(int status, String retryAfterHeader, String body) {
        if (status >= 200 && status < 300) {
            return PublishResult.success();
        }
        String error = "HTTP " + status + bodyExcerpt(body);
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return PublishResult.retryable(error, parseRetryAfterSeconds(retryAfterHeader));
        }
        return PublishResult.permanent(error);
    }

    /** Only the delay-seconds form is supported; an HTTP-date value is ignored. */
    static Duration parseRetryAfterSeconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String bodyExcerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return ": " + (trimmed.length() <= MAX_BODY_IN_ERROR ? trimmed : trimmed.substring(0, MAX_BODY_IN_ERROR) + "...");
    }
}
