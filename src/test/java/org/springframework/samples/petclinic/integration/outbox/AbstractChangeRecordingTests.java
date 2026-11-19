package org.springframework.samples.petclinic.integration.outbox;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.event.OwnerDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.OwnerPayloadV1;
import org.springframework.samples.petclinic.integration.event.PetDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.PetPayloadV1;
import org.springframework.samples.petclinic.integration.event.VisitDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.VisitPayloadV1;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.model.Visit;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1/T4: owner, pet and visit changes are recorded in the business transaction
 * (FR-1, FR-3, FR-5, FR-6, FR-7, NFR-2).
 * Subclasses run the same tests under each repository profile (FR-4).
 */
abstract class AbstractChangeRecordingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected ClinicService clinicService;

    @Autowired
    protected ChangeRecorder changeRecorder;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    void recordsCreatedEventWhenOwnerIsCreated() {
        Owner owner = newOwner("Created");

        clinicService.saveOwner(owner);

        List<Map<String, Object>> events = eventsFor("owner/" + owner.getId());
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event.get("event_type")).isEqualTo("petclinic.owner.created.v1");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(((Number) event.get("attempts")).intValue()).isZero();
        assertThat(event.get("cause")).isNull();
        assertThat(UUID.fromString((String) event.get("event_id"))).isNotNull();
        assertThat(JSON.readValue((String) event.get("payload"), OwnerPayloadV1.class))
            .isEqualTo(new OwnerPayloadV1(owner.getId(), "Sam", "Created", "4 Evans Street", "Wollongong", "4444444444"));
    }

    @Test
    @Transactional
    void recordsUpdatedEventWithNewStateWhenOwnerIsUpdated() {
        Owner owner = newOwner("Before");
        clinicService.saveOwner(owner);

        owner.setLastName("After");
        clinicService.saveOwner(owner);

        List<Map<String, Object>> events = eventsFor("owner/" + owner.getId());
        assertThat(events).extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.owner.created.v1", "petclinic.owner.updated.v1");
        assertThat(sequence(events.get(1))).isGreaterThan(sequence(events.get(0)));
        assertThat(JSON.readValue((String) events.get(1).get("payload"), OwnerPayloadV1.class).lastName())
            .isEqualTo("After");
        assertThat(events.get(0).get("event_id")).isNotEqualTo(events.get(1).get("event_id"));
    }

    @Test
    @Transactional
    void recordsDeletedEventWhenOwnerIsDeleted() {
        Owner owner = newOwner("Deleted");
        clinicService.saveOwner(owner);

        clinicService.deleteOwner(owner);

        List<Map<String, Object>> events = eventsFor("owner/" + owner.getId());
        assertThat(events).extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.owner.created.v1", "petclinic.owner.deleted.v1");
        assertThat(JSON.readValue((String) events.get(1).get("payload"), OwnerDeletedPayloadV1.class))
            .isEqualTo(new OwnerDeletedPayloadV1(owner.getId()));
    }

    @Test
    @Transactional
    void deletingAnOwnerKeepsTheSharedPetType() {
        Owner owner = clinicService.findOwnerById(1);
        int petTypeId = owner.getPets().get(0).getType().getId();
        long lastEventIdBefore = lastEventId();

        clinicService.deleteOwner(owner);

        assertThat(eventsFor("owner/1")).filteredOn(e -> sequence(e) > lastEventIdBefore)
            .extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.owner.deleted.v1");
        assertThat(clinicService.findPetTypeById(petTypeId)).isNotNull();
    }

    @Test
    @Transactional
    void recordsPetCreatedAndUpdatedEvents() {
        Pet pet = newPetForOwner6("Rex");

        clinicService.savePet(pet);
        pet.setName("Max");
        clinicService.savePet(pet);

        List<Map<String, Object>> events = eventsFor("pet/" + pet.getId());
        assertThat(events).extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.pet.created.v1", "petclinic.pet.updated.v1");
        String created = (String) events.get(0).get("payload");
        assertThat(JSON.readValue(created, PetPayloadV1.class))
            .isEqualTo(new PetPayloadV1(pet.getId(), "Rex", LocalDate.of(2020, 1, 2), 6, 2, "dog"));
        assertThat(created).contains("\"birthDate\":\"2020-01-02\"");
        assertThat(JSON.readValue((String) events.get(1).get("payload"), PetPayloadV1.class).name()).isEqualTo("Max");
    }

    @Test
    @Transactional
    void recordsPetDeletedEvent() {
        Pet pet = newPetForOwner6("Gone");
        clinicService.savePet(pet);

        clinicService.deletePet(pet);

        List<Map<String, Object>> events = eventsFor("pet/" + pet.getId());
        assertThat(events).extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.pet.created.v1", "petclinic.pet.deleted.v1");
        assertThat(JSON.readValue((String) events.get(1).get("payload"), PetDeletedPayloadV1.class))
            .isEqualTo(new PetDeletedPayloadV1(pet.getId(), 6));
    }

    @Test
    @Transactional
    void recordsVisitCreatedUpdatedAndDeletedEvents() {
        Pet pet7 = clinicService.findPetById(7);
        Visit visit = new Visit();
        pet7.addVisit(visit);
        visit.setDate(LocalDate.of(2026, 11, 19));
        visit.setDescription("checkup");

        clinicService.saveVisit(visit);
        visit.setDescription("checkup and vaccination");
        clinicService.saveVisit(visit);
        clinicService.deleteVisit(visit);

        List<Map<String, Object>> events = eventsFor("visit/" + visit.getId());
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly(
            "petclinic.visit.created.v1", "petclinic.visit.updated.v1", "petclinic.visit.deleted.v1");
        String created = (String) events.get(0).get("payload");
        assertThat(JSON.readValue(created, VisitPayloadV1.class))
            .isEqualTo(new VisitPayloadV1(visit.getId(), 7, LocalDate.of(2026, 11, 19), "checkup"));
        assertThat(created).contains("\"date\":\"2026-11-19\"");
        assertThat(JSON.readValue((String) events.get(1).get("payload"), VisitPayloadV1.class).description())
            .isEqualTo("checkup and vaccination");
        assertThat(JSON.readValue((String) events.get(2).get("payload"), VisitDeletedPayloadV1.class))
            .isEqualTo(new VisitDeletedPayloadV1(visit.getId(), 7));
    }

    @Test
    @Transactional
    void deletingAnExistingVisitRecordsOneEvent() {
        Visit visit = clinicService.findVisitById(1);
        long lastEventIdBefore = lastEventId();

        clinicService.deleteVisit(visit);

        assertThat(eventsFor("visit/1")).filteredOn(e -> sequence(e) > lastEventIdBefore)
            .extracting(e -> e.get("event_type"))
            .containsExactly("petclinic.visit.deleted.v1");
    }

    @Test
    void recordsNothingWhenTheTransactionRollsBack() {
        Owner owner = newOwner("RolledBack");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            clinicService.saveOwner(owner);
            assertThat(eventsFor("owner/" + owner.getId())).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(eventsFor("owner/" + owner.getId())).isEmpty();
    }

    @Test
    void recorderRefusesToRunOutsideATransaction() {
        Owner owner = newOwner("NoTransaction");
        owner.setId(999_999);

        assertThatThrownBy(() -> changeRecorder.ownerSaved(owner, true))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(eventsFor("owner/999999")).isEmpty();
    }

    private List<Map<String, Object>> eventsFor(String subject) {
        return jdbc.queryForList("SELECT * FROM outbox_event WHERE subject = ? ORDER BY id", subject);
    }

    private long lastEventId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM outbox_event", Long.class);
        return id == null ? 0 : id;
    }

    private static long sequence(Map<String, Object> event) {
        return ((Number) event.get("id")).longValue();
    }

    private Pet newPetForOwner6(String name) {
        Owner owner6 = clinicService.findOwnerById(6);
        Pet pet = new Pet();
        pet.setName(name);
        pet.setBirthDate(LocalDate.of(2020, 1, 2));
        pet.setType(clinicService.findPetTypeById(2));
        owner6.addPet(pet);
        return pet;
    }

    private static Owner newOwner(String lastName) {
        Owner owner = new Owner();
        owner.setFirstName("Sam");
        owner.setLastName(lastName);
        owner.setAddress("4 Evans Street");
        owner.setCity("Wollongong");
        owner.setTelephone("4444444444");
        return owner;
    }
}
