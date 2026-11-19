package org.springframework.samples.petclinic.integration.event;

public enum ChangeType {

    CREATED("created"),
    UPDATED("updated"),
    DELETED("deleted");

    private final String code;

    ChangeType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
