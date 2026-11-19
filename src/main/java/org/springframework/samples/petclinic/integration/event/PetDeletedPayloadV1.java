package org.springframework.samples.petclinic.integration.event;

public record PetDeletedPayloadV1(Integer id, Integer ownerId) {
}
