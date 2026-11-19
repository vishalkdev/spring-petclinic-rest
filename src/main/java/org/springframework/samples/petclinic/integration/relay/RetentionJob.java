package org.springframework.samples.petclinic.integration.relay;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.stereotype.Component;

/**
 * Keeps the outbox table bounded by deleting DELIVERED events older than the retention period
 * (NFR-13). Undelivered events are never deleted (NFR-5).
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final OutboxRepository outbox;
    private final Clock clock;
    private final Duration keepDelivered;

    public RetentionJob(OutboxRepository outbox, Clock clock, IntegrationProperties properties) {
        this.outbox = outbox;
        this.clock = clock;
        this.keepDelivered = properties.retention().delivered();
    }

    /** Returns how many delivered events were deleted. */
    public int purge() {
        int deleted = outbox.deleteDeliveredBefore(clock.instant().minus(keepDelivered));
        if (deleted > 0) {
            log.info("Purged {} delivered events older than {}", deleted, keepDelivered);
        }
        return deleted;
    }
}
