package org.springframework.samples.petclinic.integration.observe;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.outbox.OutboxStatus;
import org.springframework.stereotype.Component;

/**
 * Shows the outbox backlog under {@code /actuator/health} but always reports UP (NFR-11): an outage
 * of the external system must not make PetClinic look unhealthy and get restarted. Alerting uses the
 * metrics instead.
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxRepository outbox;
    private final OutboxMetrics metrics;

    public OutboxHealthIndicator(OutboxRepository outbox, OutboxMetrics metrics) {
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        return Health.up()
            .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
            .withDetail("failed", outbox.countByStatus(OutboxStatus.FAILED))
            .withDetail("oldestPendingAgeSeconds", metrics.oldestPendingAgeSeconds())
            .build();
    }
}
