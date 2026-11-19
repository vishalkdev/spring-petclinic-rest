package org.springframework.samples.petclinic.integration.event;

import java.time.LocalDate;

import org.springframework.samples.petclinic.model.Visit;

/** Snapshot of a visit after a create or update. */
public record VisitPayloadV1(Integer id, Integer petId, LocalDate date, String description) {

    public static VisitPayloadV1 from(Visit visit) {
        return new VisitPayloadV1(
            visit.getId(),
            visit.getPet() == null ? null : visit.getPet().getId(),
            visit.getDate(),
            visit.getDescription());
    }
}
