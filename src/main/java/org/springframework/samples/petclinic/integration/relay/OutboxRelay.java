package org.springframework.samples.petclinic.integration.relay;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.publish.EventPublisher;
import org.springframework.samples.petclinic.integration.publish.PublishResult;
import org.springframework.stereotype.Component;

/**
 * Publishes due events (FR-8, FR-9). Not transactional on purpose: claiming, publishing and
 * recording the outcome are separate steps, so no DB transaction is open during HTTP I/O.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final IntegrationProperties.Relay settings;
    private final String workerId = "relay-" + UUID.randomUUID();

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                       IntegrationProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.settings = properties.relay();
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
            PublishResult result = publisher.publish(event);
            Instant finished = clock.instant();
            if (result.isSuccess()) {
                outbox.markDelivered(event.id(), workerId, finished);
                log.info("Delivered event {} subject={} type={} attempt={}",
                    event.eventId(), event.subject(), event.eventType(), event.attempts() + 1);
            } else {
                outbox.recordFailedAttempt(event.id(), workerId, result.error(), finished);
                log.warn("Delivery failed for event {} subject={} attempt={}: {}",
                    event.eventId(), event.subject(), event.attempts() + 1, result.error());
            }
        }
        return attempted;
    }

    public String workerId() {
        return workerId;
    }
}
