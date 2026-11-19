package org.springframework.samples.petclinic.integration.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.outbox.OutboxStatus;
import org.springframework.samples.petclinic.rest.api.IntegrationAdminApi;
import org.springframework.samples.petclinic.rest.dto.OutboxEventDto;
import org.springframework.samples.petclinic.rest.dto.OutboxRetryResultDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API for the outbox (NFR-10): list events by status and put FAILED events back in the queue.
 * Re-queued events keep their event id and sequence, so consumers can still deduplicate them.
 */
@RestController
@CrossOrigin(exposedHeaders = "errors, content-type")
@RequestMapping("api")
public class OutboxAdminController implements IntegrationAdminApi {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxAdminController(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @PreAuthorize("hasRole(@roles.ADMIN)")
    @Override
    public ResponseEntity<List<OutboxEventDto>> listOutboxEvents(String status, Integer limit) {
        OutboxStatus wanted;
        try {
            wanted = OutboxStatus.valueOf((status == null ? "FAILED" : status).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        int max = limit == null ? 50 : limit;
        return ResponseEntity.ok(outbox.findByStatus(wanted, max).stream().map(OutboxAdminController::toDto).toList());
    }

    @PreAuthorize("hasRole(@roles.ADMIN)")
    @Override
    public ResponseEntity<OutboxEventDto> retryOutboxEvent(Long id) {
        if (outbox.findById(id).isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        if (!outbox.requeueFailed(id, clock.instant())) {
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }
        return ResponseEntity.ok(toDto(outbox.findById(id).orElseThrow()));
    }

    @PreAuthorize("hasRole(@roles.ADMIN)")
    @Override
    public ResponseEntity<OutboxRetryResultDto> retryFailedOutboxEvents() {
        return ResponseEntity.ok(new OutboxRetryResultDto().requeued(outbox.requeueAllFailed(clock.instant())));
    }

    private static OutboxEventDto toDto(OutboxEvent event) {
        return new OutboxEventDto()
            .id(event.id())
            .eventId(event.eventId())
            .subject(event.subject())
            .type(event.eventType())
            .cause(event.cause())
            .status(event.status().name())
            .attempts(event.attempts())
            .lastError(event.lastError())
            .createdAt(offset(event.createdAt()))
            .nextAttemptAt(offset(event.nextAttemptAt()));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
