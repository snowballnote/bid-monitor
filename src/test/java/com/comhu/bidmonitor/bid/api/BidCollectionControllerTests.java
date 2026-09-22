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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockitoBean
    private ManualBidCollectionCoordinator coordinator;

    @MockitoBean
    private G2bApiService g2bApiService;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM bid_notice_version");
        jdbcTemplate.update("DELETE FROM bid_notice");
        jdbcTemplate.update("DELETE FROM bid_collection_lock");
        jdbcTemplate.update("DELETE FROM bid_collection_run");
        jdbcTemplate.update("DELETE FROM bid_source_state");
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
                .andExpect(jsonPath("$.items[0].analysisStatus").doesNotExist())
                .andExpect(jsonPath("$.items[0].analysisResult").doesNotExist())
                .andExpect(jsonPath("$.items[0].relevant").doesNotExist());
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
    void preservesExistingRealtimeBidRangeEndpoint() throws Exception {
        when(g2bApiService.getTargetBidQualificationList(START, END, Set.of("6146", "1468")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/bids/target/qualification/range")
                        .param("startDate", "2026-09-21")
                        .param("endDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    private ManualBidCollectionResult result(
            ManualBidCollectionResult.Status status,
            List<BidSourcePersistenceResult> sources,
            Map<String, Long> runIds
    ) {
        return new ManualBidCollectionResult(status, START, END, sources, List.of("D2B"), runIds);
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
                .analysisStatus("MATCHED")
                .analysisResult("internal")
                .contentHash(String.valueOf(hashCharacter).repeat(64))
                .firstSeenAt(Instant.parse("2026-09-22T01:00:00Z"))
                .lastSeenAt(Instant.parse("2026-09-22T02:00:00Z"))
                .build());
    }
}
