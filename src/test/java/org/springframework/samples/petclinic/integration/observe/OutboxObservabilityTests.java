package org.springframework.samples.petclinic.integration.observe;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;
import org.springframework.samples.petclinic.integration.outbox.OutboxRepository;
import org.springframework.samples.petclinic.integration.publish.EventPublisher;
import org.springframework.samples.petclinic.integration.relay.BackoffPolicy;
import org.springframework.samples.petclinic.integration.relay.OutboxRelay;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7: metrics (NFR-8), per-attempt logs (NFR-9) and a health indicator that stays UP (NFR-11).
 * Uses its own database, so the backlog consists only of what each test creates; each test cleans up.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:hsqldb:mem:observability")
@DirtiesContext
class OutboxObservabilityTests {

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
    private OutboxHealthIndicator health;

    @Test
    void exposesTheBacklogAndPublishOutcomesAsMetrics() {
        jdbc.update("DELETE FROM outbox_event");
        String rejected = eventIdOf(saveOwner("Rejected"));
        String unavailable = eventIdOf(saveOwner("Unavailable"));
        externalSystem.stubFor(post("/events").withHeader("Idempotency-Key", equalTo(rejected))
            .willReturn(aResponse().withStatus(400)));
        externalSystem.stubFor(post("/events").withHeader("Idempotency-Key", equalTo(unavailable))
            .willReturn(aResponse().withStatus(503)));
        Instant later = Instant.now().plusSeconds(60);
        OutboxMetrics metrics = new OutboxMetrics(outbox, java.time.Clock.fixed(later, java.time.ZoneOffset.UTC));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        relay(later, metrics).runOnce();

        assertThat(registry.get(OutboxMetrics.EVENTS).tag("status", "pending").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(OutboxMetrics.EVENTS).tag("status", "failed").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isBetween(55.0, 65.0);
        assertThat(registry.get(OutboxMetrics.PUBLISH).tag("outcome", "failed").timer().count()).isEqualTo(1);
        assertThat(registry.get(OutboxMetrics.PUBLISH).tag("outcome", "retry").timer().count()).isEqualTo(1);

        externalSystem.resetAll();
        externalSystem.stubFor(post("/events").willReturn(ok()));
        relay(later.plusSeconds(10), metrics).runOnce();

        assertThat(registry.get(OutboxMetrics.PUBLISH).tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(OutboxMetrics.EVENTS).tag("status", "pending").gauge().value()).isZero();
        assertThat(registry.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isZero();
    }

    @Test
    void logsEveryAttemptWithTheEventId() {
        jdbc.update("DELETE FROM outbox_event");
        String retried = eventIdOf(saveOwner("Logged"));
        String parked = eventIdOf(saveOwner("LoggedAndParked"));
        externalSystem.stubFor(post("/events").withHeader("Idempotency-Key", equalTo(retried))
            .willReturn(aResponse().withStatus(503)));
        externalSystem.stubFor(post("/events").withHeader("Idempotency-Key", equalTo(parked))
            .willReturn(aResponse().withStatus(400)));
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        relayLogger.addAppender(logs);
        try {
            relay(Instant.now().plusSeconds(5), new OutboxMetrics(outbox, java.time.Clock.systemUTC())).runOnce();
        } finally {
            relayLogger.detachAppender(logs);
        }

        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains(retried, "attempt=1", "HTTP 503");
        });
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains(parked, "Parked", "HTTP 400");
        });
    }

    @Test
    void healthStaysUpAndShowsTheBacklog() {
        jdbc.update("DELETE FROM outbox_event");
        String parked = eventIdOf(saveOwner("Unhealthy"));
        saveOwner("Waiting");
        externalSystem.stubFor(post("/events").withHeader("Idempotency-Key", equalTo(parked))
            .willReturn(aResponse().withStatus(400)));
        externalSystem.stubFor(post("/events").atPriority(10).willReturn(aResponse().withStatus(503)));
        relay(Instant.now().plusSeconds(5), new OutboxMetrics(outbox, java.time.Clock.systemUTC())).runOnce();

        Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        Map<String, Object> details = result.getDetails();
        assertThat(details).containsEntry("pending", 1L).containsEntry("failed", 1L).containsKey("oldestPendingAgeSeconds");
    }

    private OutboxRelay relay(Instant now, OutboxMetrics metrics) {
        IntegrationProperties.Retry retry =
            new IntegrationProperties.Retry(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), 15);
        return new OutboxRelay(outbox, publisher, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC), properties,
            new BackoffPolicy(retry, () -> 1.0), metrics);
    }

    private Owner saveOwner(String lastName) {
        Owner owner = new Owner();
        owner.setFirstName("Observed");
        owner.setLastName(lastName);
        owner.setAddress("2 Gauge Street");
        owner.setCity("Perth");
        owner.setTelephone("7777777777");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> clinicService.saveOwner(owner));
        return owner;
    }

    private String eventIdOf(Owner owner) {
        List<String> ids = jdbc.queryForList("SELECT event_id FROM outbox_event WHERE subject = ? ORDER BY id",
            String.class, "owner/" + owner.getId());
        return ids.get(ids.size() - 1);
    }
}
