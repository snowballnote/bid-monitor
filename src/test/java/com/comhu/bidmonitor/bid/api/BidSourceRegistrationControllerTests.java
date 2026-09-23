package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.source.d2b.D2bBidCollector;
import com.comhu.bidmonitor.bid.source.koreaexpressway.KoreaExpresswayBidCollector;
import com.comhu.bidmonitor.service.G2bApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-source-registration-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false",
        "bid.collection.scheduler.enabled=false"
})
class BidSourceRegistrationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ManualBidCollectionCoordinator coordinator;

    @MockitoBean
    private G2bApiService g2bApiService;

    @MockitoBean
    private KoreaExpresswayBidCollector koreaExpresswayBidCollector;

    @MockitoBean
    private D2bBidCollector d2bBidCollector;

    @BeforeEach
    void clearRegistrations() {
        jdbcTemplate.update("DELETE FROM bid_source_registration");
    }

    @Test
    void registersNormalizedUrlAsPendingDisabledMetadataOnly() throws Exception {
        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "sourceName", "  Example Tenders  ",
                                "siteUrl", "HTTPS://Example.COM:443/notices/../bids"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", 
                        org.hamcrest.Matchers.matchesPattern("/api/bid-source-registrations/[0-9]+")))
                .andExpect(jsonPath("$.sourceId").isNumber())
                .andExpect(jsonPath("$.sourceName").value("Example Tenders"))
                .andExpect(jsonPath("$.siteUrl").value("https://example.com/bids"))
                .andExpect(jsonPath("$.registrationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.collectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verifyNoInteractions(coordinator, g2bApiService, koreaExpresswayBidCollector, d2bBidCollector);
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_state WHERE source_code = 'EXAMPLE_TENDERS'",
                Integer.class
        ));
    }

    @Test
    void rejectsDuplicateAfterUrlNormalization() throws Exception {
        register("First", "HTTPS://Example.COM:443");

        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "sourceName", "Second",
                                "siteUrl", "https://example.com/"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_URL_ALREADY_REGISTERED"));
    }

    @Test
    void rejectsInvalidAndInternalNetworkUrls() throws Exception {
        String[] rejectedUrls = {
                "ftp://example.com/notices",
                "https://localhost/notices",
                "http://127.0.0.1/notices",
                "http://127.1/notices",
                "http://0177.0.0.1/notices",
                "http://0x7f000001/notices",
                "http://10.20.30.40/notices",
                "http://172.16.1.2/notices",
                "http://192.168.1.2/notices",
                "http://169.254.169.254/latest/meta-data",
                "http://[::1]/notices",
                "https://user:password@example.com/notices",
                "https://example.com/notices#section"
        };
        for (String siteUrl : rejectedUrls) {
            mockMvc.perform(post("/api/bid-source-registrations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("sourceName", "Rejected", "siteUrl", siteUrl))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        String oversized = "https://example.com/" + "a".repeat(2048);
        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", "Oversized", "siteUrl", oversized))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsLatestFirstListAndSingleRegistration() throws Exception {
        register("First", "https://first.example/notices");
        register("Second", "https://second.example/notices");
        Long secondId = jdbcTemplate.queryForObject(
                "SELECT source_id FROM bid_source_registration WHERE source_name = 'Second'",
                Long.class
        );

        mockMvc.perform(get("/api/bid-source-registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceName").value("Second"))
                .andExpect(jsonPath("$[1].sourceName").value("First"));

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}", secondId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(secondId))
                .andExpect(jsonPath("$.sourceName").value("Second"));

        mockMvc.perform(get("/api/bid-source-registrations/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_REGISTRATION_NOT_FOUND"));
    }

    @Test
    void rejectsClientManagedStatusMethodAndExecutionFields() throws Exception {
        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "sourceName", "Unsafe Override",
                                "siteUrl", "https://override.example/notices",
                                "registrationStatus", "APPROVED",
                                "collectionMethod", "PUBLIC_PAGE",
                                "executionEnabled", true
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Registration state and execution settings are server-managed."));

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration",
                Integer.class
        ));
    }

    private void register(String sourceName, String siteUrl) throws Exception {
        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", sourceName, "siteUrl", siteUrl))))
                .andExpect(status().isCreated());
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
