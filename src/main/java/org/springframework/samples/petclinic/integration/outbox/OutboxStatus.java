package org.springframework.samples.petclinic.integration.outbox;

public enum OutboxStatus {
    PENDING,
    DELIVERED,
    FAILED
}
