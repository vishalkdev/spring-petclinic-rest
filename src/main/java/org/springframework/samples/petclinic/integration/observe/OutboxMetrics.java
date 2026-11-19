package org.springframework.samples.petclinic.integration.observe;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.outbox.OutboxStatus;
import org.springframework.stereotype.Component;

/**
 * Outbox metrics (NFR-8). Gauges read the table on each scrape (three indexed queries); the oldest
 * pending age is the signal to alert on. Spring Boot binds this to the meter registry when one exists;
 * without a registry everything here is a no-op.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    public static final String EVENTS = "petclinic.outbox.events";
    public static final String OLDEST_PENDING_AGE = "petclinic.outbox.oldest.pending.age";
    public static final String PUBLISH = "petclinic.outbox.publish";

    public enum Outcome {
        SUCCESS("success"),
        RETRY("retry"),
        FAILED("failed");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final OutboxRepository outbox;
    private final Clock clock;
    private volatile MeterRegistry registry;

    public OutboxMetrics(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(EVENTS, () -> outbox.countByStatus(OutboxStatus.PENDING))
            .tag("status", "pending").description("Events waiting to be delivered").register(registry);
        Gauge.builder(EVENTS, () -> outbox.countByStatus(OutboxStatus.FAILED))
            .tag("status", "failed").description("Events parked after a permanent error or exhausted retries")
            .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, this::oldestPendingAgeSeconds)
            .baseUnit("seconds").description("Age of the oldest event still waiting to be delivered")
            .register(registry);
        this.registry = registry;
    }

    public double oldestPendingAgeSeconds() {
        Instant oldest = outbox.oldestPendingCreatedAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }

    public void recordPublish(Outcome outcome, Duration duration) {
        MeterRegistry current = registry;
        if (current != null) {
            Timer.builder(PUBLISH).tag("outcome", outcome.tag)
                .description("Publish attempts by outcome").register(current).record(duration);
        }
    }
}
