package org.springframework.samples.petclinic.integration.relay;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.config.RelaySchedulingConfig;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.latestEvent;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.saveOwnerCommitted;

/**
 * T2: the relay publishes recorded events to the external system (FR-8, FR-9, NFR-12, NFR-14).
 * The relay is driven by calling {@code runOnce()}; publishing is disabled, so nothing runs on a schedule.
 * Uses its own in-memory database because these tests commit data.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:hsqldb:mem:relaydelivery")
class RelayDeliveryTests {

    @RegisterExtension
    static WireMockExtension externalSystem = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void externalSystemEndpoint(DynamicPropertyRegistry registry) {
        registry.add("petclinic.integration.publisher.endpoint", () -> externalSystem.baseUrl() + "/events");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private ClinicService clinicService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    @SuppressWarnings("unchecked")
    void deliversAPendingEventAsACloudEvent() {
        externalSystem.stubFor(post("/events").willReturn(ok()));
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Delivered");
        Map<String, Object> event = latestEvent(jdbc, owner);
        String eventId = (String) event.get("event_id");

        relay.runOnce();

        LoggedRequest request = singleRequestFor(eventId);
        assertThat(request.getHeader("Content-Type")).startsWith("application/cloudevents+json");
        Map<String, Object> body = JSON.readValue(request.getBodyAsString(), Map.class);
        assertThat(body)
            .containsEntry("specversion", "1.0")
            .containsEntry("id", eventId)
            .containsEntry("source", "/petclinic")
            .containsEntry("type", "petclinic.owner.created.v1")
            .containsEntry("subject", "owner/" + owner.getId())
            .containsEntry("datacontenttype", "application/json")
            .containsKey("time")
            .doesNotContainKey("cause");
        assertThat(((Number) body.get("seq")).longValue()).isEqualTo(((Number) event.get("id")).longValue());
        assertThat((Map<String, Object>) body.get("data")).containsEntry("lastName", "Delivered");

        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("delivered_at")).isNotNull();
        assertThat(row.get("locked_by")).isNull();
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void doesNotSendAnEventAgainOnceItIsDelivered() {
        externalSystem.stubFor(post("/events").willReturn(ok()));
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "OnlyOnce");
        String eventId = (String) latestEvent(jdbc, owner).get("event_id");

        relay.runOnce();
        relay.runOnce();

        assertThat(requestsFor(eventId)).hasSize(1);
    }

    @Test
    void keepsTheEventPendingWhenTheExternalSystemFails() {
        externalSystem.stubFor(post("/events").willReturn(aResponse().withStatus(503)));
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Unavailable");

        relay.runOnce();

        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("503");
        assertThat(row.get("locked_by")).isNull();
    }

    @Test
    void leavesAnEventAloneWhileAnotherWorkerHoldsItsLease() {
        externalSystem.stubFor(post("/events").willReturn(ok()));
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Leased");
        String eventId = (String) latestEvent(jdbc, owner).get("event_id");
        jdbc.update("UPDATE outbox_event SET locked_until = ?, locked_by = ? WHERE event_id = ?",
            LocalDateTime.now(ZoneOffset.UTC).plusHours(1), "other-relay", eventId);

        relay.runOnce();

        assertThat(requestsFor(eventId)).isEmpty();
        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("locked_by")).isEqualTo("other-relay");
    }

    @Test
    void doesNotScheduleTheRelayWhenPublishingIsDisabled() {
        assertThat(context.getBeanNamesForType(RelaySchedulingConfig.class)).isEmpty();
    }

    private List<LoggedRequest> requestsFor(String eventId) {
        return externalSystem.findAll(postRequestedFor(urlEqualTo("/events"))
            .withHeader("Idempotency-Key", equalTo(eventId)));
    }

    private LoggedRequest singleRequestFor(String eventId) {
        List<LoggedRequest> requests = requestsFor(eventId);
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }
}
