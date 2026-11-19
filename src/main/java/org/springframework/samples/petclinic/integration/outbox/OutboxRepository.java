package org.springframework.samples.petclinic.integration.outbox;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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

    /**
     * Pending events that are due and not leased by a worker, oldest first. An event is only returned
     * when no older PENDING event exists for the same subject, which keeps per-record order (NFR-6).
     * FAILED events are not PENDING, so they never block newer ones (NFR-7).
     */
    public List<OutboxEvent> findDue(Instant now, int limit) {
        LocalDateTime timestamp = utc(now);
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM outbox_event e "
                    + "WHERE e.status = ? AND e.next_attempt_at <= ? "
                    + "AND (e.locked_until IS NULL OR e.locked_until < ?) "
                    + "AND NOT EXISTS (SELECT 1 FROM outbox_event earlier "
                    + "WHERE earlier.subject = e.subject AND earlier.status = ? AND earlier.id < e.id) "
                    + "ORDER BY e.id");
            statement.setString(1, OutboxStatus.PENDING.name());
            statement.setObject(2, timestamp);
            statement.setObject(3, timestamp);
            statement.setString(4, OutboxStatus.PENDING.name());
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

    public List<OutboxEvent> findByStatus(OutboxStatus status, int limit) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM outbox_event WHERE status = ? ORDER BY id");
            statement.setString(1, status.name());
            statement.setMaxRows(limit);
            return statement;
        }, OutboxRepository::mapRow);
    }

    public Optional<OutboxEvent> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM outbox_event WHERE id = ?", OutboxRepository::mapRow, id)
            .stream().findFirst();
    }

    /** Puts one FAILED event back to PENDING with zero attempts (NFR-10). Returns false if it is not FAILED. */
    public boolean requeueFailed(long id, Instant now) {
        return jdbc.update(
            "UPDATE outbox_event SET status = ?, attempts = 0, next_attempt_at = ?, locked_until = NULL, locked_by = NULL "
                + "WHERE id = ? AND status = ?",
            OutboxStatus.PENDING.name(), utc(now), id, OutboxStatus.FAILED.name()) == 1;
    }

    /** Puts every FAILED event back to PENDING with zero attempts. Returns how many were re-queued. */
    public int requeueAllFailed(Instant now) {
        return jdbc.update(
            "UPDATE outbox_event SET status = ?, attempts = 0, next_attempt_at = ?, locked_until = NULL, locked_by = NULL "
                + "WHERE status = ?",
            OutboxStatus.PENDING.name(), utc(now), OutboxStatus.FAILED.name());
    }

    public long countByStatus(OutboxStatus status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status = ?", Long.class, status.name());
        return count == null ? 0 : count;
    }

    /** Creation time of the oldest PENDING event, or null when nothing is pending. */
    public Instant oldestPendingCreatedAt() {
        return instant(jdbc.queryForObject("SELECT MIN(created_at) FROM outbox_event WHERE status = ?",
            LocalDateTime.class, OutboxStatus.PENDING.name()));
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
