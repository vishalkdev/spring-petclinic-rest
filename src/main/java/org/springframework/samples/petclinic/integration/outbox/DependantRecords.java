package org.springframework.samples.petclinic.integration.outbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Finds the pets and visits a delete is about to remove (D1). Reads the database directly instead of
 * walking entity collections, so it is the same under every profile and does not change JPA session
 * state that the existing delete code relies on.
 */
@Repository
public class DependantRecords {

    public record PetWithVisits(int petId, int ownerId, List<Integer> visitIds) {
    }

    private static final String PETS_WITH_VISITS =
        "SELECT p.id AS pet_id, p.owner_id AS owner_id, v.id AS visit_id "
            + "FROM pets p LEFT JOIN visits v ON v.pet_id = p.id WHERE ";

    private final JdbcTemplate jdbc;

    public DependantRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PetWithVisits> petsOfOwner(int ownerId) {
        return petsWhere("p.owner_id = ?", ownerId);
    }

    public List<PetWithVisits> petsOfType(int typeId) {
        return petsWhere("p.type_id = ?", typeId);
    }

    public PetWithVisits pet(int petId) {
        List<PetWithVisits> pets = petsWhere("p.id = ?", petId);
        return pets.isEmpty() ? null : pets.get(0);
    }

    private List<PetWithVisits> petsWhere(String condition, int id) {
        Map<Integer, PetWithVisits> pets = new LinkedHashMap<>();
        jdbc.query(PETS_WITH_VISITS + condition + " ORDER BY p.id, v.id", rs -> {
            int petId = rs.getInt("pet_id");
            PetWithVisits pet = pets.computeIfAbsent(petId,
                key -> new PetWithVisits(key, readInt(rs, "owner_id"), new ArrayList<>()));
            Integer visitId = rs.getObject("visit_id", Integer.class);
            if (visitId != null) {
                pet.visitIds().add(visitId);
            }
        }, id);
        return new ArrayList<>(pets.values());
    }

    private static int readInt(java.sql.ResultSet rs, String column) {
        try {
            return rs.getInt(column);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
