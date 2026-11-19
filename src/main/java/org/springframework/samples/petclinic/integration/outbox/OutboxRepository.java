package org.springframework.samples.petclinic.integration.outbox;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code outbox_event} table. Uses plain JDBC so it behaves the same under the
 * jdbc, jpa and spring-data-jpa profiles. Timestamps are stored as UTC. Every relay update is a
 * single conditional statement, so no transaction is held while an event is being published.
 */
@Repository
public class OutboxRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final String COLUMNS =
        "id, event_id, subject, event_type, cause, payload, status, attempts, next_attempt_at, last_error, created_at";

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(String eventId, String subject, String eventType, String cause,
                              String payload, Instant now) {
        LocalDateTime timestamp = utc(now);
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

    /** Pending events that are due and not leased by a worker, oldest first. */
    public List<OutboxEvent> findDue(Instant now, int limit) {
        LocalDateTime timestamp = utc(now);
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM outbox_event "
                    + "WHERE status = ? AND next_attempt_at <= ? "
                    + "AND (locked_until IS NULL OR locked_until < ?) "
                    + "ORDER BY id");
            statement.setString(1, OutboxStatus.PENDING.name());
            statement.setObject(2, timestamp);
            statement.setObject(3, timestamp);
            statement.setMaxRows(limit);
            return statement;
        }, OutboxRepository::mapRow);
    }

    /** Takes a lease on a pending event. Returns false if another worker holds it (ADR-006). */
    public boolean tryClaim(long id, String workerId, Instant now, Instant leaseUntil) {
        return jdbc.update(
            "UPDATE outbox_event SET locked_until = ?, locked_by = ? "
                + "WHERE id = ? AND status = ? AND (locked_until IS NULL OR locked_until < ?)",
            utc(leaseUntil), workerId, id, OutboxStatus.PENDING.name(), utc(now)) == 1;
    }

    public void markDelivered(long id, String workerId, Instant now) {
        jdbc.update(
            "UPDATE outbox_event SET status = ?, attempts = attempts + 1, delivered_at = ?, last_error = NULL, "
                + "locked_until = NULL, locked_by = NULL WHERE id = ? AND locked_by = ?",
            OutboxStatus.DELIVERED.name(), utc(now), id, workerId);
    }

    /** Records a failed attempt and releases the lease; the event stays PENDING. */
    public void recordFailedAttempt(long id, String workerId, String error, Instant nextAttemptAt) {
        jdbc.update(
            "UPDATE outbox_event SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, "
                + "locked_until = NULL, locked_by = NULL WHERE id = ? AND locked_by = ?",
            truncate(error), utc(nextAttemptAt), id, workerId);
    }

    /** Parks the event as FAILED (permanent error or retries exhausted) and releases the lease. */
    public void markFailed(long id, String workerId, String error) {
        jdbc.update(
            "UPDATE outbox_event SET status = ?, attempts = attempts + 1, last_error = ?, "
                + "locked_until = NULL, locked_by = NULL WHERE id = ? AND locked_by = ?",
            OutboxStatus.FAILED.name(), truncate(error), id, workerId);
    }

    private static OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
            rs.getLong("id"),
            rs.getString("event_id"),
            rs.getString("subject"),
            rs.getString("event_type"),
            rs.getString("cause"),
            rs.getString("payload"),
            OutboxStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            instant(rs.getObject("next_attempt_at", LocalDateTime.class)),
            rs.getString("last_error"),
            instant(rs.getObject("created_at", LocalDateTime.class)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
