package org.springframework.samples.petclinic.integration.outbox;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.samples.petclinic.integration.event.ChangeType;
import org.springframework.samples.petclinic.integration.event.OwnerDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.OwnerPayloadV1;
import org.springframework.samples.petclinic.integration.event.PetDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.PetPayloadV1;
import org.springframework.samples.petclinic.integration.event.RecordType;
import org.springframework.samples.petclinic.integration.event.VisitDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.VisitPayloadV1;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.model.Visit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records change events in the caller's transaction (FR-1, FR-3, NFR-2). Must be called after
 * the business write so that generated ids exist.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ChangeRecorder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxRepository outbox;
    private final Clock clock;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactory;

    public ChangeRecorder(OutboxRepository outbox, Clock clock,
                          ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        this.outbox = outbox;
        this.clock = clock;
        this.entityManagerFactory = entityManagerFactory;
    }

    public void ownerSaved(Owner owner, boolean created) {
        record(RecordType.OWNER, owner.getId(), created ? ChangeType.CREATED : ChangeType.UPDATED,
            OwnerPayloadV1.from(owner), null);
    }

    public void ownerDeleted(Owner owner) {
        record(RecordType.OWNER, owner.getId(), ChangeType.DELETED,
            new OwnerDeletedPayloadV1(owner.getId()), null);
    }

    public void petSaved(Pet pet, boolean created) {
        record(RecordType.PET, pet.getId(), created ? ChangeType.CREATED : ChangeType.UPDATED,
            PetPayloadV1.from(pet), null);
    }

    public void petDeleted(Pet pet) {
        record(RecordType.PET, pet.getId(), ChangeType.DELETED,
            new PetDeletedPayloadV1(pet.getId(), pet.getOwner() == null ? null : pet.getOwner().getId()), null);
    }

    public void visitSaved(Visit visit, boolean created) {
        record(RecordType.VISIT, visit.getId(), created ? ChangeType.CREATED : ChangeType.UPDATED,
            VisitPayloadV1.from(visit), null);
    }

    public void visitDeleted(Visit visit) {
        record(RecordType.VISIT, visit.getId(), ChangeType.DELETED,
            new VisitDeletedPayloadV1(visit.getId(), visit.getPet() == null ? null : visit.getPet().getId()), null);
    }

    private void record(RecordType recordType, Integer id, ChangeType change, Object payload, String cause) {
        Objects.requireNonNull(id, "record must have an id before its change is recorded");
        flushPendingJpaChanges();
        outbox.insertPending(
            UUID.randomUUID().toString(),
            recordType.subject(id),
            recordType.eventType(change),
            cause,
            JSON.writeValueAsString(payload),
            clock.instant());
    }

    /**
     * Under JPA, UPDATE and DELETE statements are normally deferred to commit. Flushing first makes
     * the business write (and its row lock) happen before the event row gets its id, which is what
     * keeps sequence order equal to change order for one record (D3).
     */
    private void flushPendingJpaChanges() {
        EntityManagerFactory factory = entityManagerFactory.getIfAvailable();
        if (factory != null
            && TransactionSynchronizationManager.getResource(factory) instanceof EntityManagerHolder holder) {
            holder.getEntityManager().flush();
        }
    }
}
