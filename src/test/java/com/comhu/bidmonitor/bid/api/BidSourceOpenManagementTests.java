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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:open-management;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false",
        "bid.collection.scheduler.enabled=false"
})
class BidSourceOpenManagementTests {

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
    void localUiCanReviewWithoutAuthenticationAndSuppliesAuditActor() throws Exception {
        long sourceId = register("Local UI source", "https://local-ui.example/notices");

        mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/review", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("UNDER_REVIEW"));

        assertEquals("local-ui", jdbcTemplate.queryForObject("""
                SELECT actor FROM bid_source_registration_audit
                WHERE source_id = ? AND action = 'REVIEW_STATUS_CHANGED'
                """, String.class, sourceId));
    }

    @Test
    void publicRegistrationAndCheckRemainAvailable() throws Exception {
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

    @Test
    void devH2ConsoleAllowsOnlySameOriginFrames() throws Exception {
        mockMvc.perform(get("/h2-console/"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
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
