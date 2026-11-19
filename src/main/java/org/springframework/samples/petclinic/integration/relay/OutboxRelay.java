package org.springframework.samples.petclinic.integration.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.observe.OutboxMetrics;
import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.publish.EventPublisher;
import org.springframework.samples.petclinic.integration.publish.PublishResult;
import org.springframework.stereotype.Component;

/**
 * Publishes due events (FR-8, FR-9) and handles failures (NFR-3, NFR-4). Not transactional on
 * purpose: claiming, publishing and recording the outcome are separate steps, so no DB
 * transaction is open during HTTP I/O.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final IntegrationProperties.Relay settings;
    private final BackoffPolicy backoff;
    private final OutboxMetrics metrics;
    private final String workerId = "relay-" + UUID.randomUUID();

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                       IntegrationProperties properties, BackoffPolicy backoff, OutboxMetrics metrics) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.settings = properties.relay();
        this.backoff = backoff;
        this.metrics = metrics;
    }

    /** Runs one polling cycle and returns how many events this worker attempted. */
    public int runOnce() {
        Instant now = clock.instant();
        List<OutboxEvent> due = outbox.findDue(now, settings.batchSize());
        int attempted = 0;
        for (OutboxEvent event : due) {
            if (!outbox.tryClaim(event.id(), workerId, now, now.plus(settings.lease()))) {
                continue;
            }
            attempted++;
            Instant started = clock.instant();
            PublishResult result = publishSafely(event);
            handle(event, result, Duration.between(started, clock.instant()));
        }
        return attempted;
    }

    private PublishResult publishSafely(OutboxEvent event) {
        try {
            return publisher.publish(event);
        } catch (RuntimeException e) {
            // e.g. a payload that cannot be serialised: retrying would fail the same way
            return PublishResult.permanent("Unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handle(OutboxEvent event, PublishResult result, Duration took) {
        int attempt = event.attempts() + 1;
        Instant finished = clock.instant();
        switch (result.outcome()) {
            case SUCCESS -> {
                outbox.markDelivered(event.id(), workerId, finished);
                metrics.recordPublish(OutboxMetrics.Outcome.SUCCESS, took);
                log.info("Delivered event {} subject={} type={} attempt={}",
                    event.eventId(), event.subject(), event.eventType(), attempt);
            }
            case RETRYABLE -> {
                if (attempt >= backoff.maxAttempts()) {
                    outbox.markFailed(event.id(), workerId, result.error());
                    metrics.recordPublish(OutboxMetrics.Outcome.FAILED, took);
                    log.error("Parked event {} subject={} after {} attempts: {}",
                        event.eventId(), event.subject(), attempt, result.error());
                } else {
                    Instant next = finished.plus(backoff.delayAfter(attempt, result.retryAfter()));
                    outbox.recordFailedAttempt(event.id(), workerId, result.error(), next);
                    metrics.recordPublish(OutboxMetrics.Outcome.RETRY, took);
                    log.warn("Delivery failed for event {} subject={} attempt={}, retry at {}: {}",
                        event.eventId(), event.subject(), attempt, next, result.error());
                }
            }
            case PERMANENT -> {
                outbox.markFailed(event.id(), workerId, result.error());
                metrics.recordPublish(OutboxMetrics.Outcome.FAILED, took);
                log.error("Parked event {} subject={} on permanent error at attempt {}: {}",
                    event.eventId(), event.subject(), attempt, result.error());
            }
        }
    }

    public String workerId() {
        return workerId;
    }
}
