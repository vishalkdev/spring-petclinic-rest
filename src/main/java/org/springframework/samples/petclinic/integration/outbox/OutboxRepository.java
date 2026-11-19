package org.springframework.samples.petclinic.integration.outbox;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code outbox_event} table. Uses plain JDBC so it behaves the same under the
 * jdbc, jpa and spring-data-jpa profiles. Timestamps are stored as UTC.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(String eventId, String subject, String eventType, String cause,
                              String payload, Instant now) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update(
            "INSERT INTO outbox_event "
                + "(event_id, subject, event_type, cause, payload, status, attempts, next_attempt_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
            eventId,
            subject,
            eventType,
            new SqlParameterValue(Types.VARCHAR, cause),
            payload,
            OutboxStatus.PENDING.name(),
            timestamp,
            timestamp);
    }
}
