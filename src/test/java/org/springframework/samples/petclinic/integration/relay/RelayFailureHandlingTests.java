package org.springframework.samples.petclinic.integration.relay;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.observe.OutboxMetrics;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.publish.EventPublisher;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.latestEvent;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.saveOwnerCommitted;

/**
 * T3: temporary failures are retried on a persisted schedule, permanent ones park the event
 * (NFR-3, NFR-4). Each test builds its own relay with a movable clock and a fixed jitter of 1.0,
 * so the delays are exactly 2 s, 4 s, 8 s, ...
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:hsqldb:mem:relayfailures",
    "petclinic.integration.publisher.read-timeout=500ms"
})
class RelayFailureHandlingTests {

    @RegisterExtension
    static WireMockExtension externalSystem = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void externalSystemEndpoint(DynamicPropertyRegistry registry) {
        registry.add("petclinic.integration.publisher.endpoint", () -> externalSystem.baseUrl() + "/events");
    }

    @Autowired
    private ClinicService clinicService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private IntegrationProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxMetrics metrics;

    private MutableClock clock;

    @BeforeEach
    void acceptOtherEvents() {
        // events left over from other tests in this database are simply accepted
        externalSystem.stubFor(post("/events").atPriority(10).willReturn(ok()));
        clock = new MutableClock(Instant.now().plusSeconds(5));
    }

    @Test
    void retriesWithBackoffUntilTheExternalSystemRecovers() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Recovering");
        String eventId = eventId(owner);
        stubRecoveringAfterTwoFailures(eventId);
        OutboxRelay relay = relay(3);

        relay.runOnce();
        relay.runOnce();
        assertThat(attemptsSent(eventId)).as("not retried before its backoff").isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        relay.runOnce();
        clock.advance(Duration.ofSeconds(4));
        relay.runOnce();

        assertThat(attemptsSent(eventId)).isEqualTo(3);
        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
    }

    @Test
    void retriesAfterATimeout() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Slow");
        String eventId = eventId(owner);
        externalSystem.stubFor(post("/events").atPriority(1)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(ok().withFixedDelay(2_000)));

        relay(15).runOnce();

        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("ResourceAccessException");
    }

    @Test
    void parksTheEventAtOnceOnAPermanentError() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Rejected");
        String eventId = eventId(owner);
        externalSystem.stubFor(post("/events").atPriority(1)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(400).withBody("missing field 'type'")));
        OutboxRelay relay = relay(15);

        relay.runOnce();
        clock.advance(Duration.ofHours(1));
        relay.runOnce();

        assertThat(attemptsSent(eventId)).isEqualTo(1);
        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).isEqualTo("HTTP 400: missing field 'type'");
    }

    @Test
    void parksTheEventWhenRetriesRunOut() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Exhausted");
        String eventId = eventId(owner);
        stubAlwaysUnavailable(eventId);
        OutboxRelay relay = relay(3);

        for (int i = 0; i < 5; i++) {
            relay.runOnce();
            clock.advance(Duration.ofHours(1));
        }

        assertThat(attemptsSent(eventId)).isEqualTo(3);
        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
        assertThat((String) row.get("last_error")).isEqualTo("HTTP 503");
    }

    @Test
    void keepsTheRetryScheduleAcrossARestart() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Restarted");
        String eventId = eventId(owner);
        stubAlwaysUnavailable(eventId);
        relay(15).runOnce();

        // a new relay instance stands in for the application after a restart
        externalSystem.resetAll();
        externalSystem.stubFor(post("/events").atPriority(10).willReturn(ok()));
        OutboxRelay afterRestart = relay(15);
        afterRestart.runOnce();
        assertThat(attemptsSent(eventId)).as("schedule from before the restart is respected").isZero();

        clock.advance(Duration.ofSeconds(2));
        afterRestart.runOnce();

        assertThat(attemptsSent(eventId)).isEqualTo(1);
        Map<String, Object> row = latestEvent(jdbc, owner);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void waitsAsLongAsTheExternalSystemAsksWithRetryAfter() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Throttled");
        String eventId = eventId(owner);
        externalSystem.stubFor(post("/events").atPriority(1)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
        Instant start = clock.instant();

        relay(15).runOnce();

        LocalDateTime nextAttempt = jdbc.queryForObject(
            "SELECT next_attempt_at FROM outbox_event WHERE event_id = ?", LocalDateTime.class, eventId);
        assertThat(nextAttempt).isCloseTo(
            LocalDateTime.ofInstant(start.plusSeconds(120), ZoneOffset.UTC), within(1, ChronoUnit.MILLIS));
    }

    private OutboxRelay relay(int maxAttempts) {
        IntegrationProperties.Retry retry =
            new IntegrationProperties.Retry(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), maxAttempts);
        return new OutboxRelay(outbox, publisher, clock, properties, new BackoffPolicy(retry, () -> 1.0), metrics);
    }

    private void stubRecoveringAfterTwoFailures(String eventId) {
        String scenario = "recovering-" + eventId;
        externalSystem.stubFor(post("/events").atPriority(1).inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(503)).willSetStateTo("failed-once"));
        externalSystem.stubFor(post("/events").atPriority(1).inScenario(scenario)
            .whenScenarioStateIs("failed-once")
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(503)).willSetStateTo("failed-twice"));
        externalSystem.stubFor(post("/events").atPriority(1).inScenario(scenario)
            .whenScenarioStateIs("failed-twice")
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(ok()));
    }

    private void stubAlwaysUnavailable(String eventId) {
        externalSystem.stubFor(post("/events").atPriority(1)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(503)));
    }

    private int attemptsSent(String eventId) {
        return externalSystem.findAll(postRequestedFor(urlEqualTo("/events"))
            .withHeader("Idempotency-Key", equalTo(eventId))).size();
    }

    private String eventId(Owner owner) {
        return (String) latestEvent(jdbc, owner).get("event_id");
    }
}
