package com.comhu.bidmonitor.bid.api;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionException;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionResult;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.bid.source.d2b.promotion.D2bCandidateEvidenceService;
import com.comhu.bidmonitor.bid.source.d2b.promotion.D2bCandidateObservation;
import com.comhu.bidmonitor.service.G2bApiService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-collection-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class BidCollectionControllerTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 21);
    private static final LocalDate END = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BidCollectionRunRepository runRepository;

    @Autowired
    private BidSourceStateRepository stateRepository;

    @Autowired
    private BidNoticeRepository noticeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AllowedIndustryCodeProvider allowedIndustryCodeProvider;

    @Autowired
    private D2bCandidateEvidenceService evidenceService;

    @MockitoBean
    private ManualBidCollectionCoordinator coordinator;

    @MockitoBean
    private G2bApiService g2bApiService;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM bid_source_candidate");
        allowedIndustryCodeProvider.replaceCodes(List.of("6146", "1468"));
        jdbcTemplate.update("DELETE FROM bid_collection_run_notice");
        jdbcTemplate.update("DELETE FROM bid_notice_version");
        jdbcTemplate.update("DELETE FROM bid_notice");
        jdbcTemplate.update("DELETE FROM bid_collection_lock");
        jdbcTemplate.update("DELETE FROM bid_collection_run");
        jdbcTemplate.update("DELETE FROM bid_source_state");
        jdbcTemplate.update("DELETE FROM bid_source_registration WHERE site_url = 'https://catalog-filter.example/bids'");
    }

    @Test
    void returnsEmptyStoredNoticePageWithoutCallingExternalApis() throws Exception {
        mockMvc.perform(get("/api/bid-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        verifyNoInteractions(coordinator, g2bApiService);
    }

    @Test
    void returnsStoredNoticesLatestFirstWithPublicFieldsOnly() throws Exception {
        saveNotice("G2B", "old", "000", LocalDateTime.of(2026, 9, 20, 9, 0), 'a');
        saveNotice("D2B", "same-time-first", "1", LocalDateTime.of(2026, 9, 22, 9, 0), 'b');
        saveNotice("G2B", "same-time-last", "002", LocalDateTime.of(2026, 9, 22, 9, 0), 'c');

        mockMvc.perform(get("/api/bid-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("same-time-last"))
                .andExpect(jsonPath("$.items[1].sourceNoticeId").value("same-time-first"))
                .andExpect(jsonPath("$.items[2].sourceNoticeId").value("old"))
                .andExpect(jsonPath("$.items[0].sourceCode").value("G2B"))
                .andExpect(jsonPath("$.items[0].revision").value("002"))
                .andExpect(jsonPath("$.items[0].noticeNumber").value("NO-same-time-last"))
                .andExpect(jsonPath("$.items[0].title").value("Title same-time-last"))
                .andExpect(jsonPath("$.items[0].orderingOrganization").value("Ordering organization"))
                .andExpect(jsonPath("$.items[0].publishedAt").value("2026-09-22T09:00:00"))
                .andExpect(jsonPath("$.items[0].submissionDeadlineAt").exists())
                .andExpect(jsonPath("$.items[0].bidOpeningAt").exists())
                .andExpect(jsonPath("$.items[0].contractMethod").value("Restricted"))
                .andExpect(jsonPath("$.items[0].bidMethod").value("Electronic"))
                .andExpect(jsonPath("$.items[0].noticeStatus").value("Open"))
                .andExpect(jsonPath("$.items[0].detailUrl").value("https://example.test/same-time-last"))
                .andExpect(jsonPath("$.items[0].firstSeenAt").exists())
                .andExpect(jsonPath("$.items[0].lastSeenAt").exists())
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].noticeStatusCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].analysisStatus").value("MATCHED"))
                .andExpect(jsonPath("$.items[0].analysisResult").doesNotExist())
                .andExpect(jsonPath("$.items[0].relevant").doesNotExist());
    }

    @Test
    void returnsStructuredStoredAnalysisWithoutExposingInternalJson() throws Exception {
        saveNotice(
                "D2B", "analysis", "1", LocalDateTime.of(2026, 9, 22, 9, 0), 'a', "MATCHED",
                """
                        {"reviewStatus":"검토대상","reviewReason":"허용 업종 일치",
                         "externalCheckStatus":"NOT_DETECTED","externalSiteCheckRequired":false,
                         "externalSiteUrls":[],"awardMethodCategory":"QUALIFICATION_REVIEW",
                         "awardMethodStatus":"CONFIRMED","awardMethodReason":"적격심사 확인",
                         "awardMethodSource":"STRUCTURED_DETAIL","budgetAmount":"1000000",
                         "licenseLimit":"정보시스템 감리법인","participationRegion":"제한없음",
                         "awardMethodName":"적격심사","attachments":[]}
                        """
        );

        mockMvc.perform(get("/api/bid-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewStatus").value("검토대상"))
                .andExpect(jsonPath("$.items[0].reviewReason").value("허용 업종 일치"))
                .andExpect(jsonPath("$.items[0].budgetAmount").value("1000000"))
                .andExpect(jsonPath("$.items[0].licenseLimit").value("정보시스템 감리법인"))
                .andExpect(jsonPath("$.items[0].participationRegion").value("제한없음"))
                .andExpect(jsonPath("$.items[0].awardMethodName").value("적격심사"))
                .andExpect(jsonPath("$.items[0].attachments").isEmpty())
                .andExpect(jsonPath("$.items[0].analysisResult").doesNotExist());
    }

    @Test
    void exposesConfirmedLicenseCodesAndFiltersByAnyRequestedCodeBeforePaging() throws Exception {
        saveNotice(
                "G2B", "only-6146", "", LocalDateTime.of(2026, 9, 22, 9, 0), 'a', "MATCHED",
                "{\"licenseCodes\":[\"6146\"]}"
        );
        saveNotice(
                "G2B", "only-6525", "", LocalDateTime.of(2026, 9, 22, 10, 0), 'b', "MATCHED",
                "{\"licenseCodes\":[\"6525\"]}"
        );
        saveNotice(
                "G2B", "both", "", LocalDateTime.of(2026, 9, 22, 11, 0), 'c', "MATCHED",
                "{\"licenseCodes\":[\"6146\",\"6525\"]}"
        );
        saveNotice(
                "LH", "unconfirmed", "", LocalDateTime.of(2026, 9, 22, 12, 0), 'd', "추가확인필요",
                "{}"
        );

        mockMvc.perform(get("/api/bid-notices")
                        .param("licenseCode", "6525")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("both"))
                .andExpect(jsonPath("$.items[0].licenseCodes[0]").value("6146"))
                .andExpect(jsonPath("$.items[0].licenseCodes[1]").value("6525"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/bid-notices")
                        .param("licenseCode", "6146", "6525")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    void filtersLegacyStoredNoticesUsingCodesExplicitlyPublishedInLicenseText() throws Exception {
        saveNotice(
                "G2B", "legacy-license-text", "", LocalDateTime.of(2026, 9, 22, 9, 0),
                'e', "추가확인필요",
                "{\"licenseLimit\":\"소프트웨어사업자(컴퓨터관련서비스사업)/1468,측량업/5021\"}"
        );

        mockMvc.perform(get("/api/bid-notices").param("licenseCode", "1468"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("legacy-license-text"))
                .andExpect(jsonPath("$.items[0].licenseCodes[0]").value("1468"))
                .andExpect(jsonPath("$.items[0].licenseCodes[1]").value("5021"));
    }

    @Test
    void filtersStoredNoticesByInclusivePublishedDateAndSource() throws Exception {
        saveNotice("G2B", "before", "", LocalDateTime.of(2026, 9, 20, 23, 59), 'd');
        saveNotice("G2B", "start", "", LocalDateTime.of(2026, 9, 21, 0, 0), 'e');
        saveNotice("D2B", "other-source", "", LocalDateTime.of(2026, 9, 21, 12, 0), 'f');
        saveNotice("G2B", "end", "", LocalDateTime.of(2026, 9, 22, 23, 59), '1');
        saveNotice("G2B", "after", "", LocalDateTime.of(2026, 9, 23, 0, 0), '2');

        mockMvc.perform(get("/api/bid-notices")
                        .param("startDate", "2026-09-21")
                        .param("endDate", "2026-09-22")
                        .param("sourceCode", "g2b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("end"))
                .andExpect(jsonPath("$.items[1].sourceNoticeId").value("start"))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    void paginatesStoredNoticesAndReportsTotalCount() throws Exception {
        saveNotice("G2B", "first", "", LocalDateTime.of(2026, 9, 20, 9, 0), '3');
        saveNotice("G2B", "second", "", LocalDateTime.of(2026, 9, 21, 9, 0), '4');
        saveNotice("G2B", "third", "", LocalDateTime.of(2026, 9, 22, 9, 0), '5');

        mockMvc.perform(get("/api/bid-notices").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("first"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void filtersStoredNoticesBySingleOrMultipleRunIds() throws Exception {
        BidCollectionRun firstRun = saveRun("G2B", Instant.parse("2026-09-22T01:00:00Z"));
        BidCollectionRun secondRun = saveRun("D2B", Instant.parse("2026-09-22T01:01:00Z"));
        BidCollectionRun emptyRun = saveRun("LH", Instant.parse("2026-09-22T01:02:00Z"));
        saveNotice("G2B", "shared-run", "", LocalDateTime.of(2026, 9, 22, 9, 0), 'a');
        saveNotice("D2B", "second-run", "", LocalDateTime.of(2026, 9, 22, 10, 0), 'b');
        saveNotice("G2B", "historical", "", LocalDateTime.of(2026, 9, 22, 11, 0), 'c');
        BidNotice shared = noticeRepository.findByIdentity("G2B", "shared-run", "").orElseThrow();
        BidNotice second = noticeRepository.findByIdentity("D2B", "second-run", "").orElseThrow();
        noticeRepository.linkToRun(firstRun.getId(), shared.getId());
        noticeRepository.linkToRun(secondRun.getId(), shared.getId());
        noticeRepository.linkToRun(secondRun.getId(), second.getId());

        mockMvc.perform(get("/api/bid-notices").param("runId", firstRun.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("shared-run"));
        mockMvc.perform(get("/api/bid-notices")
                        .param("runId", firstRun.getId().toString(), secondRun.getId().toString())
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/bid-notices").param("runId", emptyRun.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get("/api/bid-notices"))
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    void excludesRejectedNoticesFromDefaultPagesAndTotalCountWhileKeepingThemStored() throws Exception {
        saveNotice("G2B", "review-target", "", LocalDateTime.of(2026, 9, 22, 9, 0), '6', "검토대상");
        saveNotice("KOGAS", "unknown", "", LocalDateTime.of(2026, 9, 22, 10, 0), '7', "추가확인필요");
        saveNotice("D2B", "excluded-english", "", LocalDateTime.of(2026, 9, 22, 11, 0), '8', "EXCLUDED");
        saveNotice("KOREA_EXPRESSWAY", "excluded-korean", "", LocalDateTime.of(2026, 9, 22, 12, 0), '9', "제외");

        assertEquals(4, noticeRepository.findAll().size());
        mockMvc.perform(get("/api/bid-notices").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("unknown"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/bid-notices").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceNoticeId").value("review-target"))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    void returnsEachStoredRevision() throws Exception {
        saveNotice("G2B", "revised", "000", LocalDateTime.of(2026, 9, 22, 9, 0), '6');
        saveNotice("G2B", "revised", "001", LocalDateTime.of(2026, 9, 22, 10, 0), '7');

        mockMvc.perform(get("/api/bid-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].revision").value("001"))
                .andExpect(jsonPath("$.items[1].revision").value("000"))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    void rejectsInvalidStoredNoticeQueryParameters() throws Exception {
        mockMvc.perform(get("/api/bid-notices").param("startDate", "2026-09-23")
                        .param("endDate", "2026-09-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/bid-notices").param("startDate", "not-a-date"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("sourceCode", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported bid source."));
        mockMvc.perform(get("/api/bid-notices").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("page", "NaN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("runId", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bid-notices").param("licenseCode", "6146 OR 1=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsFixedAndRegisteredCatalogSourceCodesForStoredNoticeFiltering() throws Exception {
        saveNotice("KOGAS", "kogas-one", "1", LocalDateTime.of(2026, 9, 22, 10, 0), 'a');
        jdbcTemplate.update("""
                INSERT INTO bid_source_registration (
                    source_name, site_url, source_code, registration_status, collection_method,
                    execution_enabled, check_status, detected_collection_method, checked_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'APPROVED', 'PUBLIC_PAGE', FALSE, 'REACHABLE',
                    'PUBLIC_PAGE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "신규사이트 A", "https://catalog-filter.example/bids", "CUSTOM_CATALOG");
        saveNotice("CUSTOM_CATALOG", "custom-one", "1", LocalDateTime.of(2026, 9, 22, 11, 0), 'c');

        mockMvc.perform(get("/api/bid-notices").param("sourceCode", "kogas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceCode").value("KOGAS"));
        mockMvc.perform(get("/api/bid-notices").param("sourceCode", "custom_catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sourceCode").value("CUSTOM_CATALOG"));
    }

    @Test
    void requestsManualCollectionAndReturnsRunIdentityAndCounts() throws Exception {
        when(coordinator.collect(any(), any(), any(), any())).thenReturn(result(
                ManualBidCollectionResult.Status.SUCCESS,
                List.of(source("G2B", BidSourcePersistenceResult.Status.SUCCESS, 3, 2, 1, 0, null)),
                Map.of("G2B", 41L)
        ));

        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-09-21","endDate":"2026-09-22","sourceCodes":["G2B"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.startDate").value("2026-09-21"))
                .andExpect(jsonPath("$.sources[0].runId").value(41))
                .andExpect(jsonPath("$.sources[0].collectedCount").value(3))
                .andExpect(jsonPath("$.sources[0].newCount").value(2))
                .andExpect(jsonPath("$.sources[0].updatedCount").value(1));

        verify(coordinator).collect(START, END, Set.of("6146", "1468"), Set.of("G2B"));
    }

    @Test
    void usesServerAllowedIndustryCodesInsteadOfRequestValues() throws Exception {
        allowedIndustryCodeProvider.replaceCodes(List.of("1468", "6525"));
        when(coordinator.collect(any(), any(), any(), any())).thenReturn(result(
                ManualBidCollectionResult.Status.SUCCESS, List.of(), Map.of()
        ));

        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-09-21","endDate":"2026-09-22",
                                 "allowedLicenseCodes":["6146","1468","9999"]}
                                """))
                .andExpect(status().isOk());

        verify(coordinator).collect(START, END, Set.of("1468", "6525"), Set.of());
    }

    @Test
    void getsAndAtomicallyReplacesAllowedIndustryCodes() throws Exception {
        evidenceService.saveAll(List.of(new D2bCandidateObservation("API-REEVALUATE", "1",
                Set.of("DOMESTIC_COMPETITIVE"), Set.of("COVERAGE"), "냉장고 구매", Map.of(),
                Map.of("lcnsLmttList", "6525"), "AVAILABLE")));
        String raw = jdbcTemplate.queryForObject("SELECT detail_fields FROM bid_source_candidate", String.class);
        mockMvc.perform(get("/api/settings/allowed-industry-codes"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"1468\",\"6146\"]"));

        mockMvc.perform(put("/api/settings/allowed-industry-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"1468\",\"6525\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"1468\",\"6525\"]"));

        assertEquals("[\"1468\",\"6525\"]", jdbcTemplate.queryForObject("SELECT allowed_codes FROM bid_source_candidate", String.class));
        assertEquals("INCOMPLETE", jdbcTemplate.queryForObject("SELECT qualification_state FROM bid_source_candidate", String.class));
        assertEquals(raw, jdbcTemplate.queryForObject("SELECT detail_fields FROM bid_source_candidate", String.class));

        mockMvc.perform(get("/api/settings/allowed-industry-codes"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"1468\",\"6525\"]"));
    }

    @Test
    void rejectsEmptyAndInvalidAllowedIndustryCodesAndDeduplicatesInput() throws Exception {
        mockMvc.perform(put("/api/settings/allowed-industry-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[]}"))
                .andExpect(status().isBadRequest());
        for (String invalidCode : List.of("123", "12345", "12A4")) {
            mockMvc.perform(put("/api/settings/allowed-industry-codes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"codes\":[\"" + invalidCode + "\"]}"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(put("/api/settings/allowed-industry-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"6525\",\"6525\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"6525\"]"));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allowed_industry_code", Long.class
        ));
    }

    @Test
    void nextManualCollectionUsesChangedSettingWithoutRestart() throws Exception {
        when(coordinator.collect(any(), any(), any(), any())).thenReturn(result(
                ManualBidCollectionResult.Status.SUCCESS, List.of(), Map.of()
        ));
        allowedIndustryCodeProvider.replaceCodes(List.of("1468", "6525"));

        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-09-21\",\"endDate\":\"2026-09-22\"}"))
                .andExpect(status().isOk());

        verify(coordinator).collect(START, END, Set.of("1468", "6525"), Set.of());
    }

    @Test
    void returnsPartialSuccessWithoutDiscardingSuccessfulCounts() throws Exception {
        when(coordinator.collect(any(), any(), any(), any())).thenReturn(result(
                ManualBidCollectionResult.Status.PARTIAL_SUCCESS,
                List.of(
                        source("G2B", BidSourcePersistenceResult.Status.SUCCESS, 2, 2, 0, 0, null),
                        source("KOREA_EXPRESSWAY", BidSourcePersistenceResult.Status.FAILED,
                                0, 0, 0, 0, "COLLECTION_FAILED")
                ),
                Map.of("G2B", 51L, "KOREA_EXPRESSWAY", 52L)
        ));

        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-09-21\",\"endDate\":\"2026-09-22\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.sources[0].newCount").value(2))
                .andExpect(jsonPath("$.sources[1].status").value("FAILED"))
                .andExpect(jsonPath("$.sources[1].errorCode").value("COLLECTION_FAILED"));
    }

    @Test
    void returnsServiceUnavailableForCompleteFailure() throws Exception {
        ManualBidCollectionResult failed = result(
                ManualBidCollectionResult.Status.FAILED,
                List.of(source("G2B", BidSourcePersistenceResult.Status.FAILED,
                        0, 0, 0, 0, "COLLECTION_FAILED")),
                Map.of("G2B", 61L)
        );
        when(coordinator.collect(any(), any(), any(), any()))
                .thenThrow(new ManualBidCollectionException(failed));

        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-09-21\",\"endDate\":\"2026-09-22\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.sources[0].runId").value(61));
    }

    @Test
    void rejectsInvalidDatesAndDisabledD2bSelection() throws Exception {
        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-09-23\",\"endDate\":\"2026-09-22\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        when(coordinator.collect(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Bid source is disabled: D2B"));
        mockMvc.perform(post("/api/bid-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-09-21","endDate":"2026-09-22","sourceCodes":["D2B"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bid source is disabled: D2B"));
    }

    @Test
    void returnsConflictWithCurrentRunIdAndDistinguishesQuota() throws Exception {
        ManualBidCollectionResult conflict = result(
                ManualBidCollectionResult.Status.FAILED,
                List.of(source("G2B", BidSourcePersistenceResult.Status.FAILED,
                        0, 0, 0, 0, "ALREADY_RUNNING")),
                Map.of("G2B", 71L)
        );
        ManualBidCollectionResult quota = result(
                ManualBidCollectionResult.Status.FAILED,
                List.of(source("D2B", BidSourcePersistenceResult.Status.FAILED,
                        0, 0, 0, 0, "DAILY_QUOTA_EXCEEDED")),
                Map.of("D2B", 72L)
        );
        when(coordinator.collect(any(), any(), any(), any()))
                .thenThrow(new ManualBidCollectionException(conflict))
                .thenThrow(new ManualBidCollectionException(quota));

        String body = "{\"startDate\":\"2026-09-21\",\"endDate\":\"2026-09-22\"}";
        mockMvc.perform(post("/api/bid-collections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sources[0].runId").value(71))
                .andExpect(jsonPath("$.sources[0].errorCode").value("ALREADY_RUNNING"));
        mockMvc.perform(post("/api/bid-collections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.sources[0].errorCode").value("DAILY_QUOTA_EXCEEDED"));
    }

    @Test
    void returnsRunStatusWithoutInternalLockData() throws Exception {
        BidCollectionRun run = runRepository.save(BidCollectionRun.builder()
                .sourceCode("G2B")
                .triggerType(BidCollectionRun.TriggerType.MANUAL)
                .queryStartDate(START)
                .queryEndDate(END)
                .startedAt(Instant.parse("2026-09-22T01:00:00Z"))
                .finishedAt(Instant.parse("2026-09-22T01:01:00Z"))
                .status(BidCollectionRun.Status.SUCCESS)
                .apiCallCount(null)
                .collectedCount(3)
                .newCount(2)
                .changedCount(1)
                .build());

        mockMvc.perform(get("/api/bid-collections/{runId}", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(run.getId()))
                .andExpect(jsonPath("$.sourceCode").value("G2B"))
                .andExpect(jsonPath("$.completedAt").value("2026-09-22T01:01:00Z"))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.ownerToken").doesNotExist())
                .andExpect(jsonPath("$.detailUrl").doesNotExist());
    }

    @Test
    void returnsSourceStateQuotaAndD2bDisabledWithoutSecrets() throws Exception {
        stateRepository.save(BidSourceState.builder()
                .sourceCode("D2B")
                .lastAttemptAt(Instant.parse("2026-09-22T01:00:00Z"))
                .dailyLimit(100)
                .usedCalls(7)
                .quotaDate(LocalDate.of(2026, 9, 22))
                .build());

        mockMvc.perform(get("/api/bid-sources/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sourceCode == 'D2B')].dailyLimit").value(100))
                .andExpect(jsonPath("$[?(@.sourceCode == 'D2B')].usedCalls").value(7))
                .andExpect(jsonPath("$[?(@.sourceCode == 'D2B')].executionEnabled").value(false))
                .andExpect(content().string(not(containsString("serviceKey"))))
                .andExpect(content().string(not(containsString("ownerToken"))))
                .andExpect(content().string(not(containsString("apis.data.go.kr"))));
    }

    @Test
    void returnsUnifiedCatalogWithAllFixedSourcesIncludingDisabledCollectors() throws Exception {
        mockMvc.perform(get("/api/bid-sources/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.sourceCode == 'KWATER')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.sourceCode == 'G2B')].sourceName").value("나라장터"))
                .andExpect(jsonPath("$[?(@.sourceCode == 'KOREA_EXPRESSWAY')].sourceName")
                        .value("한국도로공사"))
                .andExpect(jsonPath("$[?(@.sourceCode == 'KOGAS')].executionEnabled").value(false))
                .andExpect(jsonPath("$[?(@.sourceCode == 'D2B')].executionEnabled").value(false))
                .andExpect(jsonPath("$[*].sourceType").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("FIXED"))));
    }

    @Test
    void preservesExistingRealtimeBidRangeEndpoint() throws Exception {
        when(g2bApiService.getTargetBidQualificationList(START, END, Set.of("6146", "1468")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/bids/target/qualification/range")
                        .param("startDate", "2026-09-21")
                        .param("endDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void g2bQualificationEndpointUsesServerSettingAndIgnoresLegacyQueryValue() throws Exception {
        allowedIndustryCodeProvider.replaceCodes(List.of("1468", "6525"));
        when(g2bApiService.getTargetBidQualificationList(START, END, Set.of("1468", "6525")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/bids/target/qualification/range")
                        .param("startDate", "2026-09-21")
                        .param("endDate", "2026-09-22")
                        .param("allowedLicenseCodes", "6146"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(g2bApiService).getTargetBidQualificationList(
                START, END, Set.of("1468", "6525")
        );
    }

    @Test
    void legacyG2bListEndpointsUseCurrentServerIndustryCodes() throws Exception {
        allowedIndustryCodeProvider.replaceCodes(List.of("1468", "6525"));
        when(g2bApiService.getBidDtoList(Set.of("1468", "6525"))).thenReturn(List.of());
        when(g2bApiService.getTargetBidList(Set.of("1468", "6525"))).thenReturn(List.of());

        mockMvc.perform(get("/api/bids"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/bids/dto"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/bids/target"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(g2bApiService, org.mockito.Mockito.times(2))
                .getBidDtoList(Set.of("1468", "6525"));
        verify(g2bApiService).getTargetBidList(Set.of("1468", "6525"));
    }

    private ManualBidCollectionResult result(
            ManualBidCollectionResult.Status status,
            List<BidSourcePersistenceResult> sources,
            Map<String, Long> runIds
    ) {
        return new ManualBidCollectionResult(status, START, END, sources, List.of("D2B"), runIds);
    }

    private BidCollectionRun saveRun(String sourceCode, Instant startedAt) {
        return runRepository.save(BidCollectionRun.builder()
                .sourceCode(sourceCode)
                .triggerType(BidCollectionRun.TriggerType.MANUAL)
                .queryStartDate(START)
                .queryEndDate(END)
                .startedAt(startedAt)
                .status(BidCollectionRun.Status.RUNNING)
                .build());
    }

    private BidSourcePersistenceResult source(
            String sourceCode,
            BidSourcePersistenceResult.Status status,
            int collected,
            int added,
            int updated,
            int unchanged,
            String errorCode
    ) {
        return new BidSourcePersistenceResult(
                sourceCode, status, collected, added, updated, unchanged, errorCode
        );
    }

    private void saveNotice(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            LocalDateTime publishedAt,
            char hashCharacter
    ) {
        saveNotice(sourceCode, sourceNoticeId, revision, publishedAt, hashCharacter, "MATCHED");
    }

    private void saveNotice(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            LocalDateTime publishedAt,
            char hashCharacter,
            String analysisStatus
    ) {
        saveNotice(sourceCode, sourceNoticeId, revision, publishedAt, hashCharacter, analysisStatus, "internal");
    }

    private void saveNotice(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            LocalDateTime publishedAt,
            char hashCharacter,
            String analysisStatus,
            String analysisResult
    ) {
        noticeRepository.save(BidNotice.builder()
                .sourceCode(sourceCode)
                .sourceNoticeId(sourceNoticeId)
                .revisionKey(revision)
                .noticeNumber("NO-" + sourceNoticeId)
                .title("Title " + sourceNoticeId)
                .orderingOrganization("Ordering organization")
                .publishedAt(publishedAt)
                .submissionDeadlineAt(publishedAt.plusDays(7))
                .bidOpeningAt(publishedAt.plusDays(8))
                .contractMethod("Restricted")
                .bidMethod("Electronic")
                .noticeStatus("Open")
                .noticeStatusCode("OPEN")
                .detailUrl("https://example.test/" + sourceNoticeId)
                .relevant(true)
                .analysisStatus(analysisStatus)
                .analysisResult(analysisResult)
                .contentHash(String.valueOf(hashCharacter).repeat(64))
                .firstSeenAt(Instant.parse("2026-09-22T01:00:00Z"))
                .lastSeenAt(Instant.parse("2026-09-22T02:00:00Z"))
                .build());
    }
}
