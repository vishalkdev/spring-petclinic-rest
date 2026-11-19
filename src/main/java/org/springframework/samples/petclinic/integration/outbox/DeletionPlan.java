package org.springframework.samples.petclinic.integration.outbox;

import java.util.List;

import org.springframework.samples.petclinic.integration.event.RecordType;

/**
 * Delete events worked out before a delete runs and recorded after it, children first (D1).
 */
public record DeletionPlan(List<PlannedDelete> deletes) {

    public record PlannedDelete(RecordType recordType, int id, Object payload, String cause) {
    }
}
