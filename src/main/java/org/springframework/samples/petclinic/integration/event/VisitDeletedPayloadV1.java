package org.springframework.samples.petclinic.integration.event;

public record VisitDeletedPayloadV1(Integer id, Integer petId) {
}
