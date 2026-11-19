package org.springframework.samples.petclinic.integration.admin;

import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.integration.relay.OutboxRelay;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.rest.advice.ExceptionControllerAdvice;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T8: an operator can list FAILED events and re-queue one or all of them without a restart (NFR-10).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:hsqldb:mem:outboxadmin")
class OutboxAdminTests {

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
    private OutboxAdminController controller;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ClinicService clinicService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ExceptionControllerAdvice())
            .build();
        externalSystem.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/events").willReturn(ok()));
        // start each test from an empty outbox so lists and counts are exact
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listsParkedEvents() throws Exception {
        long id = parkedEventFor("Parked");

        String body = mockMvc.perform(get("/api/admin/outbox/events").param("status", "FAILED"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> events = JSON.readValue(body, List.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
            .containsEntry("id", (int) id)
            .containsEntry("status", "FAILED")
            .containsEntry("type", "petclinic.owner.created.v1")
            .containsEntry("lastError", "HTTP 400: rejected");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requeuesOneFailedEventWhichIsThenDeliveredWithTheSameEventId() throws Exception {
        long id = parkedEventFor("Requeued");
        String eventId = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE id = ?", String.class, id);

        String body = mockMvc.perform(post("/api/admin/outbox/events/{id}/retry", id))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Map<String, Object> requeued = JSON.readValue(body, Map.class);
        assertThat(requeued).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("eventId", eventId);

        relay.runOnce();

        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        externalSystem.verify(1, postRequestedFor(urlEqualTo("/events")).withHeader("Idempotency-Key", equalTo(eventId)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void answers404ForAnUnknownEventAnd409ForOneThatIsNotFailed() throws Exception {
        Owner owner = saveOwner("StillPending");
        long pending = jdbc.queryForObject("SELECT MAX(id) FROM outbox_event WHERE subject = ?", Long.class,
            "owner/" + owner.getId());

        mockMvc.perform(post("/api/admin/outbox/events/{id}/retry", 999_999)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/outbox/events/{id}/retry", pending)).andExpect(status().isConflict());
        assertThat(statusOf(pending)).isEqualTo("PENDING");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requeuesAllFailedEvents() throws Exception {
        long first = parkedEventFor("FirstParked");
        long second = parkedEventFor("SecondParked");

        String body = mockMvc.perform(post("/api/admin/outbox/events/retry-failed"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(JSON.readValue(body, Map.class)).containsEntry("requeued", 2);
        assertThat(statusOf(first)).isEqualTo("PENDING");
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    @Test
    @WithMockUser(roles = "OWNER_ADMIN")
    void refusesUsersWithoutTheAdminRole() {
        assertThatThrownBy(() -> controller.retryFailedOutboxEvents()).isInstanceOf(AccessDeniedException.class);
    }

    private long parkedEventFor(String lastName) {
        Owner owner = saveOwner(lastName);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM outbox_event WHERE subject = ?", Long.class,
            "owner/" + owner.getId());
        jdbc.update("UPDATE outbox_event SET status = 'FAILED', attempts = 1, last_error = 'HTTP 400: rejected' WHERE id = ?", id);
        return id;
    }

    private Owner saveOwner(String lastName) {
        Owner owner = new Owner();
        owner.setFirstName("Operator");
        owner.setLastName(lastName);
        owner.setAddress("3 Console Road");
        owner.setCity("Darwin");
        owner.setTelephone("8888888888");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> clinicService.saveOwner(owner));
        return owner;
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id);
    }
}
