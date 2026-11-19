package org.springframework.samples.petclinic.integration.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.saveOwnerCommitted;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.updateOwnerCommitted;

/**
 * T6: events for one record are delivered in order (NFR-6), a FAILED event does not block later
 * ones (NFR-7), and two relays never publish the same event concurrently (NFR-12).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:hsqldb:mem:relayordering")
class RelayOrderingTests {

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

    @BeforeEach
    void acceptOtherEvents() {
        externalSystem.stubFor(post("/events").atPriority(10).willReturn(ok()));
    }

    @Test
    void holdsLaterEventsForARecordWhileAnEarlierOneIsRetrying() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "First");
        updateOwnerCommitted(clinicService, transactionManager, owner, "Second");
        List<String> ids = eventIds(owner);
        String created = ids.get(0);
        String updated = ids.get(1);
        stubFailsOnceThenSucceeds(created);
        MutableClock clock = new MutableClock(Instant.now().plusSeconds(5));
        OutboxRelay relay = relay(clock);

        relay.runOnce();
        assertThat(sent(created)).isEqualTo(1);
        assertThat(sent(updated)).as("update waits while the create is retrying").isZero();

        clock.advance(Duration.ofSeconds(2));
        relay.runOnce();
        relay.runOnce();

        assertThat(sent(created)).isEqualTo(2);
        assertThat(sent(updated)).isEqualTo(1);
        assertThat(statusOf(created)).isEqualTo("DELIVERED");
        assertThat(statusOf(updated)).isEqualTo("DELIVERED");
        assertThat(arrivalOrder(created, updated)).containsExactly(created, created, updated);
    }

    @Test
    void doesNotLetAFailedEventBlockLaterEventsForTheSameRecord() {
        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Rejected");
        updateOwnerCommitted(clinicService, transactionManager, owner, "Accepted");
        List<String> ids = eventIds(owner);
        String created = ids.get(0);
        String updated = ids.get(1);
        externalSystem.stubFor(post("/events").atPriority(1)
            .withHeader("Idempotency-Key", equalTo(created))
            .willReturn(aResponse().withStatus(422)));
        OutboxRelay relay = relay(new MutableClock(Instant.now().plusSeconds(5)));

        relay.runOnce();
        relay.runOnce();

        assertThat(statusOf(created)).isEqualTo("FAILED");
        assertThat(statusOf(updated)).isEqualTo("DELIVERED");
        assertThat(sent(updated)).isEqualTo(1);
    }

    @Test
    void twoRelaysNeverSendTheSameEventTwice() throws Exception {
        externalSystem.stubFor(post("/events").atPriority(5).willReturn(ok().withFixedDelay(20)));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Concurrent" + i);
            ids.addAll(eventIds(owner));
        }
        OutboxRelay first = relay(Clock.systemUTC());
        OutboxRelay second = relay(Clock.systemUTC());
        assertThat(first.workerId()).isNotEqualTo(second.workerId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = List.of(pool.submit(() -> drain(first)), pool.submit(() -> drain(second)));
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).allSatisfy(id -> {
            assertThat(sent(id)).as("requests for %s", id).isEqualTo(1);
            assertThat(statusOf(id)).isEqualTo("DELIVERED");
        });
    }

    private static void drain(OutboxRelay relay) {
        for (int i = 0; i < 50 && relay.runOnce() > 0; i++) {
            // keep polling while this worker still finds work
        }
    }

    private OutboxRelay relay(Clock clock) {
        IntegrationProperties.Retry retry =
            new IntegrationProperties.Retry(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), 15);
        return new OutboxRelay(outbox, publisher, clock, properties, new BackoffPolicy(retry, () -> 1.0), metrics);
    }

    private void stubFailsOnceThenSucceeds(String eventId) {
        String scenario = "fails-once-" + eventId;
        externalSystem.stubFor(post("/events").atPriority(1).inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(aResponse().withStatus(503)).willSetStateTo("failed"));
        externalSystem.stubFor(post("/events").atPriority(1).inScenario(scenario)
            .whenScenarioStateIs("failed")
            .withHeader("Idempotency-Key", equalTo(eventId))
            .willReturn(ok()));
    }

    private List<String> eventIds(Owner owner) {
        return jdbc.queryForList("SELECT event_id FROM outbox_event WHERE subject = ? ORDER BY id",
            String.class, "owner/" + owner.getId());
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private int sent(String eventId) {
        return externalSystem.findAll(postRequestedFor(urlEqualTo("/events"))
            .withHeader("Idempotency-Key", equalTo(eventId))).size();
    }

    /** Idempotency keys of requests for the given events, oldest first. */
    private List<String> arrivalOrder(String... eventIds) {
        List<String> wanted = List.of(eventIds);
        List<String> order = new ArrayList<>();
        externalSystem.getAllServeEvents().forEach(serve -> {
            String key = serve.getRequest().getHeader("Idempotency-Key");
            if (wanted.contains(key)) {
                order.add(0, key);
            }
        });
        return order;
    }
}
