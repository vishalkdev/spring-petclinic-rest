package org.springframework.samples.petclinic.integration.relay;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.rest.advice.ExceptionControllerAdvice;
import org.springframework.samples.petclinic.rest.controller.v1.OwnerRestControllerV1;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3 / NFR-1: while the external system hangs and the scheduled relay is blocked on it, creating an
 * owner through the REST API still succeeds quickly and the change is recorded for later delivery.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:hsqldb:mem:restisolation",
    "petclinic.integration.publisher.enabled=true",
    "petclinic.integration.relay.poll-interval=50ms",
    "petclinic.integration.publisher.read-timeout=5s"
})
@DirtiesContext
class RestIsolationTests {

    @RegisterExtension
    static WireMockExtension externalSystem = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void externalSystemEndpoint(DynamicPropertyRegistry registry) {
        registry.add("petclinic.integration.publisher.endpoint", () -> externalSystem.baseUrl() + "/events");
    }

    @Autowired
    private OwnerRestControllerV1 ownerRestController;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void createsOwnersNormallyWhileTheExternalSystemHangs() throws Exception {
        externalSystem.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/events")
            .willReturn(aResponse().withStatus(503).withFixedDelay(4_000)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(ownerRestController)
            .setControllerAdvice(new ExceptionControllerAdvice())
            .build();
        String owner = """
            {"firstName":"Iso","lastName":"Lated","address":"9 Quiet Lane","city":"Hobart","telephone":"6666666666"}
            """;

        long started = System.nanoTime();
        mockMvc.perform(post("/api/owners").contentType(MediaType.APPLICATION_JSON).content(owner))
            .andExpect(status().isCreated());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT * FROM outbox_event WHERE event_type = 'petclinic.owner.created.v1' AND payload LIKE '%\"Lated\"%'");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("status")).isNotEqualTo("DELIVERED");
    }
}
