package org.springframework.samples.petclinic.integration.relay;

import java.time.Duration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.latestEvent;
import static org.springframework.samples.petclinic.integration.relay.RelayTestSupport.saveOwnerCommitted;

/**
 * T2: with publishing enabled, committed changes reach the external system without anyone calling
 * the relay (FR-8). The context is closed afterwards so its scheduler cannot affect other tests.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:hsqldb:mem:relayscheduled",
    "petclinic.integration.publisher.enabled=true",
    "petclinic.integration.relay.poll-interval=100ms"
})
@DirtiesContext
class ScheduledRelayTests {

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
    private JdbcTemplate jdbc;

    @Test
    void deliversCommittedChangesOnItsOwn() {
        externalSystem.stubFor(post("/events").willReturn(ok()));

        Owner owner = saveOwnerCommitted(clinicService, transactionManager, "Scheduled");
        String eventId = (String) latestEvent(jdbc, owner).get("event_id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(latestEvent(jdbc, owner).get("status")).isEqualTo("DELIVERED"));
        externalSystem.verify(1, postRequestedFor(urlEqualTo("/events"))
            .withHeader("Idempotency-Key", equalTo(eventId)));
    }
}
