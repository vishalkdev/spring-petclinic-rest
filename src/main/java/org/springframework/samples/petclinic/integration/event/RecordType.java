package org.springframework.samples.petclinic.integration.event;

/**
 * Kind of PetClinic record an event is about. The code is used in the event subject
 * ({@code owner/7}) and in the event type ({@code petclinic.owner.updated.v1}).
 */
public enum RecordType {

    OWNER("owner"),
    PET("pet"),
    VISIT("visit"),
    PET_TYPE("pettype");

    private final String code;

    RecordType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String subject(int id) {
        return code + "/" + id;
    }

    public String eventType(ChangeType change) {
        return "petclinic." + code + "." + change.code() + ".v1";
    }
}
