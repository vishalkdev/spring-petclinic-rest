package org.springframework.samples.petclinic.integration.outbox;

import java.time.Instant;

/**
 * One row of {@code outbox_event}. {@code id} is also the event's sequence number (D3).
 */
public record OutboxEvent(
    long id,
    String eventId,
    String subject,
    String eventType,
    String cause,
    String payload,
    OutboxStatus status,
    int attempts,
    Instant nextAttemptAt,
    String lastError,
    Instant createdAt) {
}
