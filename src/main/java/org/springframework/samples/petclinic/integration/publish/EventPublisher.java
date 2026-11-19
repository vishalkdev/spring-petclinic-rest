package org.springframework.samples.petclinic.integration.publish;

import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;

/**
 * Port to the external system (ADR-001). The HTTP webhook is the only adapter today; a broker
 * adapter could replace it without changing recording or the relay.
 */
public interface EventPublisher {

    PublishResult publish(OutboxEvent event);
}
