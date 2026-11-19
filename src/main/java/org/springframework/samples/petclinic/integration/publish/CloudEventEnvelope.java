package org.springframework.samples.petclinic.integration.publish;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.samples.petclinic.integration.outbox.OutboxEvent;

import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the CloudEvents 1.0 structured-mode JSON for an event (ADR-002).
 * {@code seq} and {@code cause} are extension attributes.
 */
public final class CloudEventEnvelope {

    public static final String SOURCE = "/petclinic";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CloudEventEnvelope() {
    }

    public static String toJson(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", event.eventId());
        envelope.put("source", SOURCE);
        envelope.put("type", event.eventType());
        envelope.put("subject", event.subject());
        envelope.put("time", event.createdAt().toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("seq", event.id());
        if (event.cause() != null) {
            envelope.put("cause", event.cause());
        }
        envelope.put("data", JSON.readTree(event.payload()));
        return JSON.writeValueAsString(envelope);
    }
}
