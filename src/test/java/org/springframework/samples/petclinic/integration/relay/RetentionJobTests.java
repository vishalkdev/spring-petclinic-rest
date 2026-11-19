package org.springframework.samples.petclinic.integration.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9: delivered events older than the retention period are purged (NFR-13); PENDING and FAILED events
 * are kept however old they are (NFR-5).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:hsqldb:mem:retention")
class RetentionJobTests {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private IntegrationProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesOnlyDeliveredEventsOlderThanTheRetentionPeriod() {
        jdbc.update("DELETE FROM outbox_event");
        String oldDelivered = insert("DELIVERED", Duration.ofDays(8));
        String recentDelivered = insert("DELIVERED", Duration.ofDays(6));
        String oldPending = insert("PENDING", Duration.ofDays(30));
        String oldFailed = insert("FAILED", Duration.ofDays(30));
        RetentionJob job = new RetentionJob(outbox, Clock.fixed(NOW, ZoneOffset.UTC), properties);

        int deleted = job.purge();

        assertThat(properties.retention().delivered()).isEqualTo(Duration.ofDays(7));
        assertThat(deleted).isEqualTo(1);
        assertThat(exists(oldDelivered)).isFalse();
        assertThat(exists(recentDelivered)).isTrue();
        assertThat(exists(oldPending)).isTrue();
        assertThat(exists(oldFailed)).isTrue();
    }

    @Test
    void doesNothingWhenThereIsNothingToPurge() {
        jdbc.update("DELETE FROM outbox_event");
        insert("PENDING", Duration.ofDays(30));

        assertThat(new RetentionJob(outbox, Clock.fixed(NOW, ZoneOffset.UTC), properties).purge()).isZero();
    }

    /** Inserts an event created (and, if DELIVERED, delivered) {@code age} before NOW. */
    private String insert(String status, Duration age) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime at = LocalDateTime.ofInstant(NOW.minus(age), ZoneOffset.UTC);
        jdbc.update("INSERT INTO outbox_event (event_id, subject, event_type, payload, status, attempts, "
                + "next_attempt_at, created_at, delivered_at) VALUES (?, 'owner/1', 'petclinic.owner.updated.v1', "
                + "'{}', ?, 1, ?, ?, ?)",
            eventId, status, at, at, "DELIVERED".equals(status) ? at : null);
        return eventId;
    }

    private boolean exists(String eventId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
        return count != null && count > 0;
    }
}
