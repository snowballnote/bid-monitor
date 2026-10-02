package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationAuditRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.source.registration.BidSourceAvailabilityChecker;
import com.comhu.bidmonitor.bid.source.d2b.D2bBidCollector;
import com.comhu.bidmonitor.bid.source.koreaexpressway.KoreaExpresswayBidCollector;
import com.comhu.bidmonitor.bid.source.kogas.KogasBidCollector;
import com.comhu.bidmonitor.service.G2bApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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

    @Autowired
    private BidSourceDiscoveryResultRepository discoveryResultRepository;

    @Autowired
    private ManualBidCollectionSourceRegistry sourceRegistry;

    @MockitoBean
    private ManualBidCollectionCoordinator coordinator;

    @MockitoBean
    private G2bApiService g2bApiService;

    @MockitoBean
    private KoreaExpresswayBidCollector koreaExpresswayBidCollector;

    @MockitoBean
    private D2bBidCollector d2bBidCollector;

    @MockitoBean
    private KogasBidCollector kogasBidCollector;

    @MockitoBean
    private BidSourceAvailabilityChecker availabilityChecker;

    @MockitoSpyBean
    private BidSourceRegistrationAuditRepository auditRepository;

    @BeforeEach
    void clearRegistrations() {
        jdbcTemplate.update("DELETE FROM bid_source_discovery_review_audit");
        jdbcTemplate.update("DELETE FROM bid_source_discovery_review");
        jdbcTemplate.update("DELETE FROM bid_source_discovery_result");
        jdbcTemplate.update("DELETE FROM bid_source_registration_audit");
        jdbcTemplate.update("DELETE FROM bid_source_registration");
        when(kogasBidCollector.sourceCode()).thenReturn("KOGAS");
        when(kogasBidCollector.registrationBindingSupported()).thenReturn(true);
        when(kogasBidCollector.executionEnabled()).thenReturn(false);
    }

    @Test
    void exposesNotAnalyzedAndStoredDiscoveryResult() throws Exception {
        register("Discovery fixture", "https://discovery.example/notices");
        long sourceId = sourceId("Discovery fixture");

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}/discovery", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryStatus").value("NOT_ANALYZED"));

        discoveryResultRepository.save(BidSourceDiscoveryResult.builder()
                .sourceId(sourceId)
                .discoveryStatus(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .listPageUrl("https://discovery.example/notices")
                .identifierConfidence(BidSourceDiscoveryResult.Confidence.MEDIUM)
                .titleConfidence(BidSourceDiscoveryResult.Confidence.HIGH)
                .deadlineConfidence(BidSourceDiscoveryResult.Confidence.NONE)
                .attachmentDetected(false)
                .paginationDetected(false)
                .reasonCodes(List.of("DETAIL_LINK_MISSING", "PAGINATION_NOT_DETECTED"))
                .analyzedAt(Instant.parse("2026-09-30T03:00:00Z"))
                .build());

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}/discovery", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryStatus").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.identifierConfidence").value("MEDIUM"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("DETAIL_LINK_MISSING"));
    }

    @Test
    void discoveryExecutionUsesTheExistingPublicCheckPolicyAndRequiresAvailabilityFirst() throws Exception {
        register("Discovery flow", "https://flow.example/notices");
        long sourceId = sourceId("Discovery flow");

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/discovery", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("AVAILABILITY_CHECK_REQUIRED"));

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}/discovery", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryStatus").value("FAILED"));
        verifyNoInteractions(availabilityChecker);
    }

    @Test
    void readyDiscoveryCreatesPendingReviewWithDetectedAndConfirmedValuesSeparated() throws Exception {
        register("Ready review", "https://ready-review.example/notices");
        long sourceId = sourceId("Ready review");
        storeDiscovery(sourceId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                "a[href]@href::{key}", "a[href]::text");

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}/discovery/review", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryStatus").value("READY"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.detected.identifierMapping").value("a[href]@href::{key}"))
                .andExpect(jsonPath("$.confirmed.identifierMapping").value("a[href]@href::{key}"))
                .andExpect(jsonPath("$.confirmed.agencyMapping").value("UNKNOWN"));

        assertEquals("PENDING_REVIEW", jdbcTemplate.queryForObject(
                "SELECT review_status FROM bid_source_discovery_review WHERE source_id = ?",
                String.class, sourceId));
    }

    @Test
    void administratorApprovesReadyDiscoveryAndCreatesAudit() throws Exception {
        register("Approve discovery", "https://approve-discovery.example/notices");
        long sourceId = sourceId("Approve discovery");
        storeDiscovery(sourceId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                "a[href]@href::{key}", "a[href]::text");

        discoveryReview(sourceId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT source_id, action, previous_status, new_status, actor, created_at
                FROM bid_source_discovery_review_audit WHERE source_id = ?
                """, sourceId);
        assertEquals(sourceId, ((Number) audit.get("SOURCE_ID")).longValue());
        assertEquals("APPROVED", audit.get("ACTION"));
        assertEquals("PENDING_REVIEW", audit.get("PREVIOUS_STATUS"));
        assertEquals("APPROVED", audit.get("NEW_STATUS"));
        assertEquals("bid-admin", audit.get("ACTOR"));
        assertNotNull(audit.get("CREATED_AT"));
    }

    @Test
    void administratorEditsMappingsBeforeApproval() throws Exception {
        register("Edit discovery", "https://edit-discovery.example/notices");
        long sourceId = sourceId("Edit discovery");
        storeDiscovery(sourceId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                "a[href]@href::{key}", "a[href]::text");

        discoveryReview(sourceId, Map.of(
                "reviewStatus", "PENDING_REVIEW",
                "titleMapping", "td.notice-title a::text",
                "agencyMapping", "UNKNOWN",
                "paginationMapping", "a.next@href"
        )).andExpect(status().isOk());
        discoveryReview(sourceId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.detected.titleMapping").value("a[href]::text"))
                .andExpect(jsonPath("$.confirmed.titleMapping").value("td.notice-title a::text"))
                .andExpect(jsonPath("$.confirmed.paginationMapping").value("a.next@href"));

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_discovery_review_audit WHERE source_id = ?",
                Integer.class, sourceId));
    }

    @Test
    void rejectsDiscoveryApprovalUnlessReadyAndRequiredMappingsExist() throws Exception {
        register("Not ready discovery", "https://not-ready-discovery.example/notices");
        long notReadyId = sourceId("Not ready discovery");
        storeDiscovery(notReadyId, BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW,
                "a[href]@href::{key}", "a[href]::text");

        discoveryReview(notReadyId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Discovery must be READY before approval."));

        register("Missing mapping discovery", "https://missing-mapping.example/notices");
        long missingId = sourceId("Missing mapping discovery");
        storeDiscovery(missingId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                null, "a[href]::text");

        discoveryReview(missingId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("identifierMapping is required for approval."));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_discovery_review_audit",
                Integer.class));
    }

    @Test
    void rejectsUnauthenticatedManagementRequests() throws Exception {
        mockMvc.perform(patch("/api/bid-source-registrations/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/binding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceCode", "KOGAS"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("executionEnabled", true))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/discovery/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reviewStatus", "APPROVED"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsDevBasicCredentialsOutsideDevProfile() throws Exception {
        mockMvc.perform(patch("/api/bid-source-registrations/1/review")
                        .with(httpBasic("dev-admin", "test-only-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsManagementRequestsWithoutAdministratorRole() throws Exception {
        mockMvc.perform(patch("/api/bid-source-registrations/1/review")
                        .with(user("reviewer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationStatus", "UNDER_REVIEW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/binding")
                        .with(user("reviewer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceCode", "KOGAS"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/activation")
                        .with(user("reviewer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("executionEnabled", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mockMvc.perform(patch("/api/bid-source-registrations/1/discovery/review")
                        .with(user("reviewer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reviewStatus", "APPROVED"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
    }

    @Test
    void recordsAdministratorAndStateChangesWithoutSensitiveColumns() throws Exception {
        when(kogasBidCollector.executionEnabled()).thenReturn(true);
        register("Audited KOGAS", "https://audited-kogas.example/notices");
        long sourceId = sourceId("Audited KOGAS");
        approveWithDetectedMethod(sourceId, "https://audited-kogas.example/notices", "PUBLIC_PAGE");
        bind(sourceId, "KOGAS").andExpect(status().isOk());
        activate(sourceId, true).andExpect(status().isOk());
        activate(sourceId, false).andExpect(status().isOk());

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, previous_status, new_status,
                       previous_source_code, new_source_code,
                       previous_execution_enabled, new_execution_enabled, actor
                FROM bid_source_registration_audit
                WHERE source_id = ? ORDER BY audit_id
                """, sourceId);
        assertEquals(List.of(
                "REVIEW_STATUS_CHANGED", "REVIEW_STATUS_CHANGED", "SOURCE_BOUND",
                "ACTIVATED", "DEACTIVATED"
        ), audits.stream().map(row -> row.get("ACTION")).toList());
        assertEquals("PENDING_REVIEW", audits.get(0).get("PREVIOUS_STATUS"));
        assertEquals("UNDER_REVIEW", audits.get(0).get("NEW_STATUS"));
        assertEquals(null, audits.get(2).get("PREVIOUS_SOURCE_CODE"));
        assertEquals("KOGAS", audits.get(2).get("NEW_SOURCE_CODE"));
        assertEquals(false, audits.get(3).get("PREVIOUS_EXECUTION_ENABLED"));
        assertEquals(true, audits.get(3).get("NEW_EXECUTION_ENABLED"));
        assertEquals(true, audits.get(4).get("PREVIOUS_EXECUTION_ENABLED"));
        assertEquals(false, audits.get(4).get("NEW_EXECUTION_ENABLED"));
        audits.forEach(row -> assertEquals("bid-admin", row.get("ACTOR")));

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'BID_SOURCE_REGISTRATION_AUDIT'
                """, String.class);
        assertFalse(columns.stream().anyMatch(column -> {
            String normalized = column.toUpperCase();
            return normalized.contains("PASSWORD") || normalized.contains("TOKEN")
                    || normalized.contains("API_KEY");
        }));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void failedManagementChangeDoesNotCreateAudit() throws Exception {
        when(kogasBidCollector.executionEnabled()).thenReturn(true);
        register("Failed Audit", "https://failed-audit.example/notices");
        long sourceId = sourceId("Failed Audit");

        activate(sourceId, true).andExpect(status().isBadRequest());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration_audit WHERE source_id = ?",
                Integer.class,
                sourceId
        ));
        assertFalse(jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM bid_source_registration WHERE source_id = ?",
                Boolean.class,
                sourceId
        ));
    }

    @Test
    void auditFailureRollsBackManagementChange() throws Exception {
        register("Audit Rollback", "https://audit-rollback.example/notices");
        long sourceId = sourceId("Audit Rollback");
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditRepository).save(any());

        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isServiceUnavailable());

        assertEquals("PENDING_REVIEW", jdbcTemplate.queryForObject(
                "SELECT registration_status FROM bid_source_registration WHERE source_id = ?",
                String.class,
                sourceId
        ));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration_audit WHERE source_id = ?",
                Integer.class,
                sourceId
        ));
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
                .andExpect(jsonPath("$.sourceCode").doesNotExist())
                .andExpect(jsonPath("$.registrationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.collectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.checkStatus").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.detectedCollectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.httpStatus").doesNotExist())
                .andExpect(jsonPath("$.checkedAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verifyNoInteractions(
                coordinator, g2bApiService, koreaExpresswayBidCollector, d2bBidCollector,
                availabilityChecker
        );
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_state WHERE source_code = 'EXAMPLE_TENDERS'",
                Integer.class
        ));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration WHERE source_code IS NOT NULL",
                Integer.class
        ));
    }

    @Test
    void rejectsBindingBeforeApprovalAndForRejectedRegistrations() throws Exception {
        register("Pending Binding", "https://pending-binding.example/notices");
        long pendingId = sourceId("Pending Binding");
        bind(pendingId, "KOGAS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        review(pendingId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        bind(pendingId, "KOGAS")
                .andExpect(status().isBadRequest());

        register("Rejected Binding", "https://rejected-binding.example/notices");
        long rejectedId = sourceId("Rejected Binding");
        review(rejectedId, Map.of(
                "registrationStatus", "UNDER_REVIEW",
                "collectionMethod", "PUBLIC_PAGE"
        )).andExpect(status().isOk());
        review(rejectedId, Map.of("registrationStatus", "REJECTED"))
                .andExpect(status().isOk());
        bind(rejectedId, "KOGAS")
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration WHERE source_code IS NOT NULL",
                Integer.class
        ));
        verify(kogasBidCollector, never()).collect(any(), any());
    }

    @Test
    void bindsApprovedCompatibleRegistrationToKogasWithoutEnablingExecution() throws Exception {
        register("Unrelated", "https://unrelated-binding.example/notices");
        register("KOGAS Binding", "https://kogas-binding.example/notices");
        long sourceId = sourceId("KOGAS Binding");
        approveWithDetectedMethod(sourceId, "https://kogas-binding.example/notices", "PUBLIC_PAGE");

        bind(sourceId, "kogas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.sourceCode").value("KOGAS"))
                .andExpect(jsonPath("$.registrationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.collectionMethod").value("PUBLIC_PAGE"))
                .andExpect(jsonPath("$.detectedCollectionMethod").value("PUBLIC_PAGE"))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT source_code, execution_enabled FROM bid_source_registration WHERE source_id = ?
                """, sourceId);
        assertEquals("KOGAS", stored.get("SOURCE_CODE"));
        assertEquals(false, stored.get("EXECUTION_ENABLED"));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void rejectsUnknownIncompatibleAndDuplicateSourceCodeBindings() throws Exception {
        register("Unknown Binding", "https://unknown-binding.example/notices");
        long unknownId = sourceId("Unknown Binding");
        approveWithDetectedMethod(unknownId, "https://unknown-binding.example/notices", "PUBLIC_PAGE");
        bind(unknownId, "D2B")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sourceCode conflicts with a fixed collector."));

        register("Incompatible Binding", "https://incompatible-binding.example/notices");
        long incompatibleId = sourceId("Incompatible Binding");
        checkAs(incompatibleId, "https://incompatible-binding.example/notices", "PUBLIC_PAGE");
        review(incompatibleId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        review(incompatibleId, Map.of(
                "registrationStatus", "APPROVED",
                "collectionMethod", "RSS"
        )).andExpect(status().isOk());
        bind(incompatibleId, "KOGAS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("The confirmed and detected collection methods must match before binding."));

        register("First KOGAS Binding", "https://first-kogas-binding.example/notices");
        long firstId = sourceId("First KOGAS Binding");
        approveWithDetectedMethod(firstId, "https://first-kogas-binding.example/notices", "PUBLIC_PAGE");
        bind(firstId, "KOGAS").andExpect(status().isOk());

        register("Second KOGAS Binding", "https://second-kogas-binding.example/notices");
        long secondId = sourceId("Second KOGAS Binding");
        approveWithDetectedMethod(secondId, "https://second-kogas-binding.example/notices", "PUBLIC_PAGE");
        bind(secondId, "KOGAS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CODE_ALREADY_BOUND"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bid_source_registration WHERE source_code = 'KOGAS'",
                Integer.class
        ));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void autoBindsStableGenericIdentityOnlyAfterBothApprovalsAndKeepsActivationSeparate() throws Exception {
        register("Stable Generic", "https://stable-generic.example/notices");
        long sourceId = sourceId("Stable Generic");
        approveWithDetectedMethod(sourceId, "https://stable-generic.example/notices", "PUBLIC_PAGE");

        bindAutomatic(sourceId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Generic binding requires READY discovery, APPROVED review, and a valid PUBLIC_PAGE config."
                ));

        storeDiscovery(sourceId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                "a[href]@href::{key}", "a[href]::text");
        discoveryReview(sourceId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isOk());

        String sourceCode = objectMapper.readTree(bindAutomatic(sourceId)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.sourceCode").value(
                                org.hamcrest.Matchers.matchesPattern("CUSTOM_[0-9A-F]{20}")))
                        .andExpect(jsonPath("$.executionEnabled").value(false))
                        .andReturn().getResponse().getContentAsString())
                .get("sourceCode").asText();
        assertFalse(sourceRegistry.sources().stream()
                .anyMatch(source -> sourceCode.equals(source.sourceCode())));

        jdbcTemplate.update("""
                UPDATE bid_source_registration
                SET source_name = 'Renamed Generic', site_url = 'https://renamed-generic.example/changed'
                WHERE source_id = ?
                """, sourceId);
        bind(sourceId, "AUTO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value(sourceCode))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bid_source_registration_audit
                WHERE source_id = ? AND action = 'SOURCE_BOUND'
                """, Integer.class, sourceId));
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT previous_source_code, new_source_code, actor
                FROM bid_source_registration_audit
                WHERE source_id = ? AND action = 'SOURCE_BOUND'
                """, sourceId);
        assertEquals(null, audit.get("PREVIOUS_SOURCE_CODE"));
        assertEquals(sourceCode, audit.get("NEW_SOURCE_CODE"));
        assertEquals("bid-admin", audit.get("ACTOR"));

        activate(sourceId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value(sourceCode))
                .andExpect(jsonPath("$.executionEnabled").value(true));
        assertTrue(sourceRegistry.sources().stream()
                .anyMatch(source -> sourceCode.equals(source.sourceCode())));
    }

    @Test
    void genericExplicitBindingPreventsFixedRegistrationAndCaseInsensitiveCollisions() throws Exception {
        register("Fixed Collision", "https://fixed-collision.example/notices");
        long fixedCollisionId = sourceId("Fixed Collision");
        approveGenericPublicPage(fixedCollisionId, "https://fixed-collision.example/notices");
        bind(fixedCollisionId, "d2b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sourceCode conflicts with a fixed collector."));

        register("First Generic", "https://first-generic.example/notices");
        long firstId = sourceId("First Generic");
        approveGenericPublicPage(firstId, "https://first-generic.example/notices");
        bind(firstId, "custom_shared_portal")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("CUSTOM_SHARED_PORTAL"));
        jdbcTemplate.update("""
                UPDATE bid_source_registration SET source_code = 'custom_shared_portal' WHERE source_id = ?
                """, firstId);

        register("Second Generic", "https://second-generic.example/notices");
        long secondId = sourceId("Second Generic");
        approveGenericPublicPage(secondId, "https://second-generic.example/notices");
        bind(secondId, "CuStOm_ShArEd_PoRtAl")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CODE_ALREADY_BOUND"));

        bind(firstId, "AUTO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("custom_shared_portal"));
        bind(firstId, "CUSTOM_REPLACEMENT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The registration is already bound to a collector."));
        assertEquals("custom_shared_portal", jdbcTemplate.queryForObject(
                "SELECT source_code FROM bid_source_registration WHERE source_id = ?",
                String.class,
                firstId
        ));
    }

    @Test
    void activatesApprovedBoundCompatibleKogasAndCanDisableItAgain() throws Exception {
        when(kogasBidCollector.executionEnabled()).thenReturn(true);
        register("Activated KOGAS", "https://activated-kogas.example/notices");
        long sourceId = sourceId("Activated KOGAS");
        approveWithDetectedMethod(sourceId, "https://activated-kogas.example/notices", "PUBLIC_PAGE");
        bind(sourceId, "KOGAS").andExpect(status().isOk());

        activate(sourceId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("KOGAS"))
                .andExpect(jsonPath("$.executionEnabled").value(true));
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM bid_source_registration WHERE source_id = ?",
                Boolean.class,
                sourceId
        ));

        activate(sourceId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false));
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM bid_source_registration WHERE source_id = ?",
                Boolean.class,
                sourceId
        ));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void rejectsActivationBeforeApprovalAndWithoutBinding() throws Exception {
        when(kogasBidCollector.executionEnabled()).thenReturn(true);
        register("Pending Activation", "https://pending-activation.example/notices");
        long pendingId = sourceId("Pending Activation");
        activate(pendingId, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only approved registrations can be activated."));

        review(pendingId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        activate(pendingId, true)
                .andExpect(status().isBadRequest());

        register("Rejected Activation", "https://rejected-activation.example/notices");
        long rejectedId = sourceId("Rejected Activation");
        review(rejectedId, Map.of(
                "registrationStatus", "UNDER_REVIEW",
                "collectionMethod", "PUBLIC_PAGE"
        )).andExpect(status().isOk());
        review(rejectedId, Map.of("registrationStatus", "REJECTED"))
                .andExpect(status().isOk());
        activate(rejectedId, true)
                .andExpect(status().isBadRequest());

        register("Unbound Activation", "https://unbound-activation.example/notices");
        long unboundId = sourceId("Unbound Activation");
        approveWithDetectedMethod(unboundId, "https://unbound-activation.example/notices", "PUBLIC_PAGE");
        activate(unboundId, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A collector binding is required before activation."));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void rejectsActivationForMethodMismatchUnknownCollectorAndDisabledConfiguration() throws Exception {
        when(kogasBidCollector.executionEnabled()).thenReturn(true);
        register("Mismatched Activation", "https://mismatch-activation.example/notices");
        long mismatchId = sourceId("Mismatched Activation");
        approveWithDetectedMethod(mismatchId, "https://mismatch-activation.example/notices", "PUBLIC_PAGE");
        bind(mismatchId, "KOGAS").andExpect(status().isOk());
        jdbcTemplate.update("""
                UPDATE bid_source_registration SET detected_collection_method = 'RSS' WHERE source_id = ?
                """, mismatchId);
        activate(mismatchId, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("The confirmed and detected collection methods must match before activation."));

        register("Unknown Activation", "https://unknown-activation.example/notices");
        long unknownId = sourceId("Unknown Activation");
        approveWithDetectedMethod(unknownId, "https://unknown-activation.example/notices", "PUBLIC_PAGE");
        jdbcTemplate.update("""
                UPDATE bid_source_registration SET source_code = 'UNKNOWN' WHERE source_id = ?
                """, unknownId);
        activate(unknownId, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("No registration-binding collector supports sourceCode."));

        when(kogasBidCollector.executionEnabled()).thenReturn(false);
        jdbcTemplate.update("""
                UPDATE bid_source_registration SET detected_collection_method = 'PUBLIC_PAGE' WHERE source_id = ?
                """, mismatchId);
        activate(mismatchId, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The collector is disabled by configuration."));
        verify(kogasBidCollector, never()).collect(any(), any());
        verifyNoInteractions(coordinator);
    }

    @Test
    void databaseRejectsExecutionWithoutApprovedBoundDeterminedRegistration() throws Exception {
        register("DB Invariant", "https://db-invariant.example/notices");
        long sourceId = sourceId("DB Invariant");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                UPDATE bid_source_registration SET execution_enabled = TRUE WHERE source_id = ?
                """, sourceId));

        jdbcTemplate.update("""
                UPDATE bid_source_registration
                SET registration_status = 'UNDER_REVIEW', collection_method = 'PUBLIC_PAGE'
                WHERE source_id = ?
                """, sourceId);
        jdbcTemplate.update("""
                UPDATE bid_source_registration SET registration_status = 'APPROVED' WHERE source_id = ?
                """, sourceId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                UPDATE bid_source_registration SET execution_enabled = TRUE WHERE source_id = ?
                """, sourceId));
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

    @Test
    void movesFromPendingThroughReviewToApprovedWithDeterminedCollectionMethod() throws Exception {
        register("Approval Source", "https://approval.example/notices");
        long sourceId = sourceId("Approval Source");

        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.collectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        review(sourceId, Map.of(
                "registrationStatus", "APPROVED",
                "collectionMethod", "OFFICIAL_API"
        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.collectionMethod").value("OFFICIAL_API"))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        org.junit.jupiter.api.Assertions.assertFalse(jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM bid_source_registration WHERE source_id = ?",
                Boolean.class,
                sourceId
        ));
        verifyNoInteractions(coordinator, g2bApiService, koreaExpresswayBidCollector, d2bBidCollector);
    }

    @Test
    void rejectsInvalidStatusJumpAndApprovalWithoutCollectionMethod() throws Exception {
        register("Invalid Transition", "https://invalid-transition.example/notices");
        long sourceId = sourceId("Invalid Transition");

        review(sourceId, Map.of(
                "registrationStatus", "APPROVED",
                "collectionMethod", "PUBLIC_PAGE"
        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());

        review(sourceId, Map.of("registrationStatus", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("collectionMethod must be determined before approval."));

        mockMvc.perform(get("/api/bid-source-registrations/{sourceId}", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.collectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.executionEnabled").value(false));
    }

    @Test
    void rejectsRegistrationAfterReviewWithoutEnablingExecution() throws Exception {
        register("Rejected Source", "https://rejected.example/notices");
        long sourceId = sourceId("Rejected Source");

        review(sourceId, Map.of(
                "registrationStatus", "UNDER_REVIEW",
                "collectionMethod", "RSS"
        )).andExpect(status().isOk());

        review(sourceId, Map.of("registrationStatus", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.collectionMethod").value("RSS"))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(coordinator, g2bApiService, koreaExpresswayBidCollector, d2bBidCollector);
    }

    @Test
    void reviewReturnsNotFoundAndRejectsExecutionChanges() throws Exception {
        review(999999L, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_REGISTRATION_NOT_FOUND"));

        register("Execution Protected", "https://execution-protected.example/notices");
        long sourceId = sourceId("Execution Protected");
        review(sourceId, Map.of(
                "registrationStatus", "UNDER_REVIEW",
                "executionEnabled", true
        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        org.junit.jupiter.api.Assertions.assertFalse(jdbcTemplate.queryForObject(
                "SELECT execution_enabled FROM bid_source_registration WHERE source_id = ?",
                Boolean.class,
                sourceId
        ));
    }

    @Test
    void explicitlyChecksAndStoresResultWithoutChangingReviewOrExecution() throws Exception {
        register("KOGAS", "https://ebid.kogas.or.kr/notices");
        long sourceId = sourceId("KOGAS");
        Instant checkedAt = Instant.parse("2026-09-28T01:02:03Z");
        when(availabilityChecker.check("https://ebid.kogas.or.kr/notices"))
                .thenReturn(new BidSourceCheckResult(
                        BidSourceRegistration.CheckStatus.REACHABLE,
                        BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                        200,
                        "text/html; charset=UTF-8",
                        checkedAt,
                        null
                ));

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.collectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.checkStatus").value("REACHABLE"))
                .andExpect(jsonPath("$.detectedCollectionMethod").value("PUBLIC_PAGE"))
                .andExpect(jsonPath("$.httpStatus").value(200))
                .andExpect(jsonPath("$.contentType").value("text/html; charset=UTF-8"))
                .andExpect(jsonPath("$.checkedAt").value("2026-09-28T01:02:03Z"))
                .andExpect(jsonPath("$.safeFailureCode").doesNotExist());

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT registration_status, collection_method, execution_enabled, check_status,
                       detected_collection_method, http_status, content_type, safe_failure_code
                FROM bid_source_registration WHERE source_id = ?
                """, sourceId);
        org.junit.jupiter.api.Assertions.assertEquals("PENDING_REVIEW", stored.get("REGISTRATION_STATUS"));
        org.junit.jupiter.api.Assertions.assertEquals("UNDETERMINED", stored.get("COLLECTION_METHOD"));
        org.junit.jupiter.api.Assertions.assertEquals(false, stored.get("EXECUTION_ENABLED"));
        org.junit.jupiter.api.Assertions.assertEquals("REACHABLE", stored.get("CHECK_STATUS"));
        org.junit.jupiter.api.Assertions.assertEquals("PUBLIC_PAGE", stored.get("DETECTED_COLLECTION_METHOD"));
        verify(availabilityChecker).check("https://ebid.kogas.or.kr/notices");
        verifyNoInteractions(coordinator, g2bApiService, koreaExpresswayBidCollector, d2bBidCollector);
    }

    @Test
    void allowsCheckDuringReviewButNotAfterApproval() throws Exception {
        register("Review Check", "https://review-check.example/notices");
        long sourceId = sourceId("Review Check");
        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        when(availabilityChecker.check("https://review-check.example/notices"))
                .thenReturn(new BidSourceCheckResult(
                        BidSourceRegistration.CheckStatus.REACHABLE,
                        BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                        200,
                        "text/html",
                        Instant.parse("2026-09-28T02:00:00Z"),
                        null
                ));

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("UNDER_REVIEW"));

        review(sourceId, Map.of(
                "registrationStatus", "APPROVED",
                "collectionMethod", "PUBLIC_PAGE"
        )).andExpect(status().isOk());
        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        register("Rejected Check", "https://rejected-check.example/notices");
        long rejectedSourceId = sourceId("Rejected Check");
        review(rejectedSourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        review(rejectedSourceId, Map.of("registrationStatus", "REJECTED"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", rejectedSourceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(availabilityChecker).check("https://review-check.example/notices");
    }

    @Test
    void rejectsDuplicateCheckBeforeTenMinuteTimeout() throws Exception {
        register("Active Check", "https://active-check.example/notices");
        long sourceId = sourceId("Active Check");
        jdbcTemplate.update("""
                UPDATE bid_source_registration
                SET check_status = 'CHECKING', check_attempt_id = 'active-attempt',
                    check_started_at = DATEADD('MINUTE', -9, CURRENT_TIMESTAMP)
                WHERE source_id = ?
                """, sourceId);

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("A site availability check is already running."));

        assertEquals("CHECKING", jdbcTemplate.queryForObject(
                "SELECT check_status FROM bid_source_registration WHERE source_id = ?",
                String.class,
                sourceId
        ));
        verifyNoInteractions(availabilityChecker);
    }

    @Test
    void retriesExpiredCheckAndDoesNotTreatPreviousAttemptAsSuccess() throws Exception {
        register("Expired Check", "https://expired-check.example/notices");
        long sourceId = sourceId("Expired Check");
        jdbcTemplate.update("""
                UPDATE bid_source_registration
                SET check_status = 'CHECKING', check_attempt_id = 'expired-attempt',
                    check_started_at = DATEADD('MINUTE', -11, CURRENT_TIMESTAMP),
                    detected_collection_method = 'PUBLIC_PAGE', http_status = 200,
                    content_type = 'text/html'
                WHERE source_id = ?
                """, sourceId);
        when(availabilityChecker.check("https://expired-check.example/notices"))
                .thenAnswer(invocation -> {
                    Map<String, Object> inProgress = jdbcTemplate.queryForMap("""
                            SELECT check_status, safe_failure_code, http_status,
                                   detected_collection_method, check_started_at
                            FROM bid_source_registration WHERE source_id = ?
                            """, sourceId);
                    assertEquals("CHECKING", inProgress.get("CHECK_STATUS"));
                    assertEquals("CHECK_TIMEOUT", inProgress.get("SAFE_FAILURE_CODE"));
                    assertEquals("UNDETERMINED", inProgress.get("DETECTED_COLLECTION_METHOD"));
                    assertEquals(null, inProgress.get("HTTP_STATUS"));
                    assertNotNull(inProgress.get("CHECK_STARTED_AT"));
                    return new BidSourceCheckResult(
                            BidSourceRegistration.CheckStatus.UNREACHABLE,
                            BidSourceRegistration.CollectionMethod.UNDETERMINED,
                            null,
                            null,
                            Instant.parse("2026-09-28T03:00:00Z"),
                            BidSourceRegistration.SafeFailureCode.CONNECTION_TIMEOUT
                    );
                });

        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkStatus").value("UNREACHABLE"))
                .andExpect(jsonPath("$.detectedCollectionMethod").value("UNDETERMINED"))
                .andExpect(jsonPath("$.httpStatus").doesNotExist())
                .andExpect(jsonPath("$.safeFailureCode").value("CONNECTION_TIMEOUT"))
                .andExpect(jsonPath("$.registrationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.executionEnabled").value(false));

        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bid_source_registration
                WHERE source_id = ? AND check_status = 'REACHABLE'
                """, Integer.class, sourceId));
    }

    @Test
    void permitsOnlyOneConcurrentCheckAndStoresStartTime() throws Exception {
        register("Concurrent Check", "https://concurrent-check.example/notices");
        long sourceId = sourceId("Concurrent Check");
        CountDownLatch checkerEntered = new CountDownLatch(1);
        CountDownLatch releaseChecker = new CountDownLatch(1);
        when(availabilityChecker.check("https://concurrent-check.example/notices"))
                .thenAnswer(invocation -> {
                    checkerEntered.countDown();
                    if (!releaseChecker.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test checker release timed out.");
                    }
                    return new BidSourceCheckResult(
                            BidSourceRegistration.CheckStatus.REACHABLE,
                            BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                            200,
                            "text/html",
                            Instant.parse("2026-09-28T04:00:00Z"),
                            null
                    );
                });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> firstStatus = executor.submit(() -> mockMvc.perform(
                            post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                    .andReturn().getResponse().getStatus());
            org.junit.jupiter.api.Assertions.assertTrue(checkerEntered.await(5, TimeUnit.SECONDS));

            assertEquals("CHECKING", jdbcTemplate.queryForObject(
                    "SELECT check_status FROM bid_source_registration WHERE source_id = ?",
                    String.class,
                    sourceId
            ));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT check_started_at FROM bid_source_registration WHERE source_id = ?",
                    java.sql.Timestamp.class,
                    sourceId
            ));
            mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("A site availability check is already running."));

            releaseChecker.countDown();
            assertEquals(200, firstStatus.get(5, TimeUnit.SECONDS));
        } finally {
            releaseChecker.countDown();
        }
        verify(availabilityChecker).check("https://concurrent-check.example/notices");
    }

    private void register(String sourceName, String siteUrl) throws Exception {
        mockMvc.perform(post("/api/bid-source-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", sourceName, "siteUrl", siteUrl))))
                .andExpect(status().isCreated());
    }

    private long sourceId(String sourceName) {
        return jdbcTemplate.queryForObject(
                "SELECT source_id FROM bid_source_registration WHERE source_name = ?",
                Long.class,
                sourceName
        );
    }

    private org.springframework.test.web.servlet.ResultActions review(
            long sourceId,
            Map<String, ?> request
    ) throws Exception {
        return mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/review", sourceId)
                .with(user("bid-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)));
    }

    private org.springframework.test.web.servlet.ResultActions bind(long sourceId, String sourceCode)
            throws Exception {
        return mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/binding", sourceId)
                .with(user("bid-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("sourceCode", sourceCode))));
    }

    private org.springframework.test.web.servlet.ResultActions bindAutomatic(long sourceId)
            throws Exception {
        return mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/binding", sourceId)
                .with(user("bid-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of())));
    }

    private org.springframework.test.web.servlet.ResultActions discoveryReview(
            long sourceId,
            Map<String, ?> request
    ) throws Exception {
        return mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/discovery/review", sourceId)
                .with(user("bid-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)));
    }

    private void storeDiscovery(
            long sourceId,
            BidSourceDiscoveryResult.DiscoveryStatus status,
            String identifierMapping,
            String titleMapping
    ) {
        discoveryResultRepository.save(BidSourceDiscoveryResult.builder()
                .sourceId(sourceId)
                .discoveryStatus(status)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .listPageUrl("https://discovery-review.example/notices")
                .detailUrlPattern("https://discovery-review.example/notices/{key}")
                .identifierMapping(identifierMapping)
                .titleMapping(titleMapping)
                .identifierConfidence(BidSourceDiscoveryResult.Confidence.HIGH)
                .titleConfidence(BidSourceDiscoveryResult.Confidence.HIGH)
                .deadlineConfidence(BidSourceDiscoveryResult.Confidence.NONE)
                .reasonCodes(List.of("DETAIL_PATTERN_DETECTED"))
                .analyzedAt(Instant.parse("2026-09-30T03:00:00Z"))
                .build());
    }

    private org.springframework.test.web.servlet.ResultActions activate(long sourceId, boolean enabled)
            throws Exception {
        return mockMvc.perform(patch("/api/bid-source-registrations/{sourceId}/activation", sourceId)
                .with(user("bid-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("executionEnabled", enabled))));
    }

    private void approveWithDetectedMethod(long sourceId, String siteUrl, String method) throws Exception {
        checkAs(sourceId, siteUrl, method);
        review(sourceId, Map.of("registrationStatus", "UNDER_REVIEW"))
                .andExpect(status().isOk());
        review(sourceId, Map.of(
                "registrationStatus", "APPROVED",
                "collectionMethod", method
        )).andExpect(status().isOk());
    }

    private void approveGenericPublicPage(long sourceId, String siteUrl) throws Exception {
        approveWithDetectedMethod(sourceId, siteUrl, "PUBLIC_PAGE");
        storeDiscovery(sourceId, BidSourceDiscoveryResult.DiscoveryStatus.READY,
                "a[href]@href::{key}", "a[href]::text");
        discoveryReview(sourceId, Map.of("reviewStatus", "APPROVED"))
                .andExpect(status().isOk());
    }

    private void checkAs(long sourceId, String siteUrl, String method) throws Exception {
        when(availabilityChecker.check(siteUrl)).thenReturn(new BidSourceCheckResult(
                BidSourceRegistration.CheckStatus.REACHABLE,
                BidSourceRegistration.CollectionMethod.valueOf(method),
                200,
                "text/html",
                Instant.parse("2026-09-29T00:00:00Z"),
                null
        ));
        mockMvc.perform(post("/api/bid-source-registrations/{sourceId}/check", sourceId))
                .andExpect(status().isOk());
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
