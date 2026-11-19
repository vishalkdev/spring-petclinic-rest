package org.springframework.samples.petclinic.integration.event;

import org.springframework.samples.petclinic.model.Owner;

/**
 * Snapshot of an owner after a create or update. Deliberately separate from the generated
 * REST DTOs so the integration contract does not change when the REST API does.
 */
public record OwnerPayloadV1(
    Integer id,
    String firstName,
    String lastName,
    String address,
    String city,
    String telephone) {

    public static OwnerPayloadV1 from(Owner owner) {
        return new OwnerPayloadV1(
            owner.getId(),
            owner.getFirstName(),
            owner.getLastName(),
            owner.getAddress(),
            owner.getCity(),
            owner.getTelephone());
    }
}
