package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.source.d2b.D2bBidCollector;
import com.comhu.bidmonitor.bid.source.kogas.KogasBidCollector;
import com.comhu.bidmonitor.bid.source.koreaexpressway.KoreaExpresswayBidCollector;
import com.comhu.bidmonitor.bid.source.registration.BidSourceAvailabilityChecker;
import com.comhu.bidmonitor.service.G2bApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "BIZ_ASSIST_DEV_ADMIN_USERNAME=dev-admin",
        "BIZ_ASSIST_DEV_ADMIN_PASSWORD=test-only-password",
        "spring.datasource.url=jdbc:h2:mem:dev-admin-security;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false",
        "bid.collection.scheduler.enabled=false"
})
class BidSourceDevAdminSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private BidSourceAvailabilityChecker availabilityChecker;

    @MockitoBean
    private ManualBidCollectionCoordinator coordinator;

    @MockitoBean
    private G2bApiService g2bApiService;

    @MockitoBean
    private KoreaExpresswayBidCollector koreaExpresswayBidCollector;

    @MockitoBean
    private KogasBidCollector kogasBidCollector;

    @MockitoBean
    private D2bBidCollector d2bBidCollector;

    @BeforeEach
    void clearRegistrations() {
        jdbcTemplate.update("DELETE FROM bid_source_discovery_review_audit");
        jdbcTemplate.update("DELETE FROM bid_source_discovery_review");
        jdbcTemplate.update("DELETE FROM bid_source_discovery_result");
        jdbcTemplate.update("DELETE FROM bid_source_registration_audit");
        jdbcTemplate.update("DELETE FROM bid_source_registration");
    }

    @Test
    void devBasicAdminCanReviewAndSuppliesAuditActor() throws Exception {
        long sourceId = register("Dev admin source", "https://dev-admin.example/notices");

        mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/review", sourceId)
                        .with(httpBasic("dev-admin", "test-only-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("UNDER_REVIEW"));

        assertEquals("dev-admin", jdbcTemplate.queryForObject("""
                SELECT actor FROM bid_source_registration_audit
                WHERE source_id = ? AND action = 'REVIEW_STATUS_CHANGED'
                """, String.class, sourceId));
    }

    @Test
    void devBasicRejectsWrongPasswordAndNonAdminAuthority() throws Exception {
        mockMvc.perform(patch("/api/bid-source-registrations/1/review")
                        .with(httpBasic("dev-admin", "wrong-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(patch("/api/bid-source-registrations/1/review")
                        .with(user("dev-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
    }

    @Test
    void publicRegistrationAndCheckRemainAvailableWithoutAuthentication() throws Exception {
        String siteUrl = "https://public-dev.example/notices";
        long sourceId = register("Public dev source", siteUrl);
        when(availabilityChecker.check(siteUrl)).thenReturn(new BidSourceCheckResult(
                BidSourceRegistration.CheckStatus.REACHABLE,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                200,
                "text/html",
                Instant.parse("2026-10-02T00:00:00Z"),
                null
        ));

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkStatus").value("REACHABLE"));
    }

    private long register(String sourceName, String siteUrl) throws Exception {
        String response = mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", sourceName, "siteUrl", siteUrl))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("sourceId").asLong();
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
