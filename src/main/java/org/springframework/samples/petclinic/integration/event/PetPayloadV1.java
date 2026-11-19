package org.springframework.samples.petclinic.integration.event;

import java.time.LocalDate;

import org.springframework.samples.petclinic.model.Pet;

/**
 * Snapshot of a pet after a create or update. Related records are referenced by id; {@code typeName}
 * is included because pet types have no events of their own (FR-7, D2).
 */
public record PetPayloadV1(
    Integer id,
    String name,
    LocalDate birthDate,
    Integer ownerId,
    Integer typeId,
    String typeName) {

    public static PetPayloadV1 from(Pet pet) {
        return new PetPayloadV1(
            pet.getId(),
            pet.getName(),
            pet.getBirthDate(),
            pet.getOwner() == null ? null : pet.getOwner().getId(),
            pet.getType() == null ? null : pet.getType().getId(),
            pet.getType() == null ? null : pet.getType().getName());
    }
}
