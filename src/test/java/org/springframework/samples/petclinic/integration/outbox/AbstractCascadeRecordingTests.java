package org.springframework.samples.petclinic.integration.outbox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.event.OwnerDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.PetDeletedPayloadV1;
import org.springframework.samples.petclinic.integration.event.VisitDeletedPayloadV1;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.model.PetType;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 / D1: a delete that removes pets and visits records a delete event for each of them, with
 * {@code cause}, before the parent's own event (FR-2). The expected records are read from the
 * database before the delete, so the tests do not depend on exact seed ids.
 */
abstract class AbstractCascadeRecordingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected ClinicService clinicService;

    @Autowired
    protected JdbcTemplate jdbc;

    @Test
    @Transactional
    void deletingAnOwnerRecordsItsPetsAndVisitsFirst() {
        Owner owner = clinicService.findOwnerById(6);
        Set<String> expectedChildren = dependantSubjects("p.owner_id = ?", 6);
        assertThat(expectedChildren).as("seed data gives owner 6 pets with visits").anyMatch(s -> s.startsWith("visit/"));
        long before = lastEventId();

        clinicService.deleteOwner(owner);

        List<Map<String, Object>> events = eventsSince(before);
        assertThat(subjects(events)).containsExactlyInAnyOrderElementsOf(withParent(expectedChildren, "owner/6"));
        Map<String, Object> last = events.get(events.size() - 1);
        assertThat(last.get("subject")).isEqualTo("owner/6");
        assertThat(last.get("cause")).isNull();
        assertThat(JSON.readValue((String) last.get("payload"), OwnerDeletedPayloadV1.class))
            .isEqualTo(new OwnerDeletedPayloadV1(6));
        assertThat(events.subList(0, events.size() - 1)).allSatisfy(e -> {
            assertThat(e.get("event_type")).asString().endsWith(".deleted.v1");
            assertThat(e.get("cause")).isEqualTo("owner/6");
        });
        assertVisitsComeBeforeTheirPet(events);
        assertPetPayloadsName(events, 6);
    }

    @Test
    @Transactional
    void deletingAPetRecordsItsVisitsFirst() {
        Pet pet = clinicService.findPetById(7);
        Set<String> expectedChildren = dependantSubjects("p.id = ?", 7);
        expectedChildren.remove("pet/7");
        assertThat(expectedChildren).as("seed data gives pet 7 visits").isNotEmpty();
        long before = lastEventId();

        clinicService.deletePet(pet);

        List<Map<String, Object>> events = eventsSince(before);
        assertThat(subjects(events)).containsExactlyInAnyOrderElementsOf(withParent(expectedChildren, "pet/7"));
        Map<String, Object> last = events.get(events.size() - 1);
        assertThat(last.get("subject")).isEqualTo("pet/7");
        assertThat(last.get("cause")).isNull();
        assertThat(JSON.readValue((String) last.get("payload"), PetDeletedPayloadV1.class))
            .isEqualTo(new PetDeletedPayloadV1(7, 6));
        assertThat(events.subList(0, events.size() - 1)).allSatisfy(e -> {
            assertThat(e.get("event_type")).isEqualTo("petclinic.visit.deleted.v1");
            assertThat(e.get("cause")).isEqualTo("pet/7");
            assertThat(JSON.readValue((String) e.get("payload"), VisitDeletedPayloadV1.class).petId()).isEqualTo(7);
        });
    }

    @Test
    @Transactional
    void deletingAPetTypeRecordsEveryPetAndVisitItRemoves() {
        PetType cat = clinicService.findPetTypeById(1);
        Set<String> expected = dependantSubjects("p.type_id = ?", 1);
        assertThat(expected).as("seed data has cats with visits").anyMatch(s -> s.startsWith("visit/"));
        long before = lastEventId();

        clinicService.deletePetType(cat);

        List<Map<String, Object>> events = eventsSince(before);
        assertThat(subjects(events)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("event_type")).asString().endsWith(".deleted.v1");
            assertThat(e.get("cause")).isEqualTo("pettype/1");
        });
        assertThat(subjects(events)).noneMatch(s -> s.startsWith("pettype/"));
        assertVisitsComeBeforeTheirPet(events);
    }

    private Set<String> dependantSubjects(String condition, int id) {
        Set<String> subjects = new HashSet<>();
        jdbc.query("SELECT p.id AS pet_id, v.id AS visit_id FROM pets p LEFT JOIN visits v ON v.pet_id = p.id WHERE "
            + condition, rs -> {
            subjects.add("pet/" + rs.getInt("pet_id"));
            Integer visitId = rs.getObject("visit_id", Integer.class);
            if (visitId != null) {
                subjects.add("visit/" + visitId);
            }
        }, id);
        return subjects;
    }

    private static Set<String> withParent(Set<String> children, String parent) {
        Set<String> all = new HashSet<>(children);
        all.add(parent);
        return all;
    }

    private void assertVisitsComeBeforeTheirPet(List<Map<String, Object>> events) {
        List<String> order = subjects(events);
        for (Map<String, Object> event : events) {
            if (event.get("event_type").equals("petclinic.visit.deleted.v1")) {
                int petId = JSON.readValue((String) event.get("payload"), VisitDeletedPayloadV1.class).petId();
                assertThat(order.indexOf(event.get("subject")))
                    .as("%s before pet/%d", event.get("subject"), petId)
                    .isLessThan(order.indexOf("pet/" + petId));
            }
        }
    }

    private void assertPetPayloadsName(List<Map<String, Object>> events, int ownerId) {
        for (Map<String, Object> event : events) {
            if (event.get("event_type").equals("petclinic.pet.deleted.v1")) {
                assertThat(JSON.readValue((String) event.get("payload"), PetDeletedPayloadV1.class).ownerId())
                    .isEqualTo(ownerId);
            }
        }
    }

    private List<Map<String, Object>> eventsSince(long id) {
        return jdbc.queryForList("SELECT * FROM outbox_event WHERE id > ? ORDER BY id", id);
    }

    private static List<String> subjects(List<Map<String, Object>> events) {
        List<String> subjects = new ArrayList<>();
        events.forEach(e -> subjects.add((String) e.get("subject")));
        return subjects;
    }

    private long lastEventId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM outbox_event", Long.class);
        return id == null ? -1 : id; // HSQLDB identities could start at 0, so never use 0 as "none"
    }
}
