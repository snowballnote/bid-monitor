package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursor;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursorRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetry;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetryRepository;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.classifier.BidRelevanceClassifier;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.G2bApiService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class D2bCoverageCollectionTests {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");

    @Test
    void buildsDateOnlyRequestAndAdvancesOnlyAfterPersistence() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) return detail("");
            return list(2, item(common("COVER-1", "건축 감리")));
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        D2bBidCollector.CollectionResult result = collector.collectMeasured(DATE, DATE);

        URI coverageRequest = transport.uris.stream()
                .filter(uri -> path(uri).equals("getDmstcCmpetBidPblancList"))
                .filter(uri -> !isFastPath(uri)).findFirst().orElseThrow();
        Map<String, String> query = query(coverageRequest);
        assertFalse(query.containsKey("bidNm"));
        assertEquals("20261015", query.get("anmtDateBegin"));
        assertEquals("20261015", query.get("anmtDateEnd"));
        assertEquals("1", query.get("pageNo"));

        BidSourceCollectionCursor before = cursors.domesticCompetitive();
        assertEquals(1, before.nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, before.status());
        assertNotNull(result.coverageToken());

        collector.completeCoverage(result.coverageToken(), true);

        BidSourceCollectionCursor after = cursors.domesticCompetitive();
        assertEquals(2, after.nextPage());
        assertEquals(2, after.totalCountSnapshot());
        assertEquals(BidSourceCollectionCursor.Status.PARTIAL, after.status());
        assertEquals(1, transport.detailCalls.get());
    }

    @Test
    void malformedPageDoesNotMoveCursor() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> isFastPath(uri)
                ? emptyList()
                : "<response><header><resultCode>00</resultCode></header><body><items/></body></response>");
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        D2bBidCollector.CollectionResult result = collector.collectMeasured(DATE, DATE);

        assertNull(result.coverageToken());
        assertEquals(1, cursors.domesticCompetitive().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, cursors.domesticCompetitive().status());
    }

    @Test
    void resumesPartialCursorAfterQuotaExhaustionAndCompletesLastPage() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        AtomicInteger allowedCalls = new AtomicInteger(22);
        D2bBidCollector.CallQuota quota = () -> {
            if (allowedCalls.getAndDecrement() <= 0) {
                throw new D2bDailyQuotaExceededException(DATE);
            }
        };
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) return detail("");
            String title = "1".equals(query(uri).get("pageNo")) ? "건축 감리" : "정보시스템 유지관리 용역";
            return list(2, item(common("PAGE-" + query(uri).get("pageNo"), title)));
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, quota, 1, 1, 1);

        D2bBidCollector.CollectionResult first = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(BidSourceCollectionCursor.Status.PARTIAL, cursors.domesticCompetitive().status());

        assertThrows(D2bBidCollector.CollectionException.class,
                () -> collector.collectMeasured(DATE, DATE));
        assertEquals(2, cursors.domesticCompetitive().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PARTIAL, cursors.domesticCompetitive().status());

        allowedCalls.set(22);
        D2bBidCollector.CollectionResult resumed = collector.collectMeasured(DATE, DATE);
        assertTrue(transport.uris.stream().filter(uri -> !isFastPath(uri))
                .anyMatch(uri -> "2".equals(query(uri).get("pageNo"))));
        assertEquals(List.of("PAGE-2"), resumed.candidates().stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
        collector.completeCoverage(resumed.coverageToken(), true);

        assertEquals(3, cursors.domesticCompetitive().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.COMPLETE, cursors.domesticCompetitive().status());
    }

    @Test
    void detailsRelevantUnknownAndIrrelevantTitles() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) return detail("<lcnsLmttList>업종코드: 6146</lcnsLmttList>");
            return list(3,
                    item(common("REL", "정보시스템 유지관리 용역")),
                    item(common("UNKNOWN", "상수도 유지관리시스템 개선 용역")),
                    item(common("IRREL", "건축 감리"))
            );
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 3, 3);

        D2bBidCollector.CollectionResult result = collector.collectMeasured(DATE, DATE);

        assertEquals(List.of("REL", "UNKNOWN", "IRREL"), result.candidates().stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
        assertEquals(3, transport.detailCalls.get());
        collector.completeCoverage(result.coverageToken(), true);
        assertEquals(BidSourceCollectionCursor.Status.COMPLETE, cursors.domesticCompetitive().status());
    }

    @Test
    void failedPersistenceLeavesTheSamePageForSafeReprocessing() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> isFastPath(uri)
                ? emptyList() : list(0));
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        D2bBidCollector.CollectionResult result = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(result.coverageToken(), false);

        assertEquals(1, cursors.domesticCompetitive().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, cursors.domesticCompetitive().status());
    }

    @Test
    void reopensCompletedLatestWindowSoNewSameDayNoticesAreNotMissed() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> isFastPath(uri)
                ? emptyList() : list(0));
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        D2bBidCollector.CollectionResult first = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(BidSourceCollectionCursor.Status.COMPLETE, cursors.domesticCompetitive().status());

        D2bBidCollector.CollectionResult second = collector.collectMeasured(DATE, DATE);

        assertNotNull(second.coverageToken());
        assertEquals(1, cursors.domesticCompetitive().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, cursors.domesticCompetitive().status());
    }

    @Test
    void irrelevantTitleWithAllowedQualificationIsKeptByCommonQualificationFirstReview() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) {
                return detail("<lcnsLmttList>업종코드: 6146</lcnsLmttList>");
            }
            return list(1, item(common("ALLOWED-IRRELEVANT", "건축 감리")));
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        List<BidQualificationDto> reviewed = review(collector.collectMeasured(DATE, DATE).candidates(),
                collector, Set.of("6146"));

        assertEquals(List.of("ALLOWED-IRRELEVANT"), reviewed.stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
    }

    @Test
    void irrelevantTitleWithQualificationMismatchFollowsExistingEvaluatorReview() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) {
                return detail("<lcnsLmttList>업종코드: 9999</lcnsLmttList>"
                        + "<sucbidrDecsnMth>적격심사제</sucbidrDecsnMth>"
                        + "<areaLmttList>제한없음</areaLmttList>");
            }
            return list(1, item(common("MISMATCH-IRRELEVANT", "건축 감리")));
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        List<BidQualificationDto> reviewed = review(collector.collectMeasured(DATE, DATE).candidates(),
                collector, Set.of("6146"));

        assertEquals(1, reviewed.size());
        assertEquals("추가확인필요", reviewed.getFirst().getReviewStatus());
        assertEquals("허용 면허조건 불일치: 9999", reviewed.getFirst().getReviewReason());
    }

    @Test
    void detailFailureIsDurablyRetriedAfterPageCursorAdvances() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        InMemoryRetryRepository retries = new InMemoryRetryRepository();
        AtomicInteger detailAttempts = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) {
                if (detailAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient detail failure");
                }
                return detail("<lcnsLmttList>업종코드: 6146</lcnsLmttList>");
            }
            return list(1, item(common("RETRY-1", "건축 감리")));
        });
        D2bBidCollector collector = coverageCollector(
                transport, cursors, retries, () -> { }, 1, 1, 1
        );

        D2bBidCollector.CollectionResult first = collector.collectMeasured(DATE, DATE);
        assertTrue(first.candidates().isEmpty());
        assertEquals(BidSourceCollectionCursor.Status.PENDING,
                cursors.domesticCompetitive().status());
        assertEquals(1, retries.size());
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(BidSourceCollectionCursor.Status.COMPLETE, cursors.domesticCompetitive().status());
        assertEquals(1, retries.size());

        D2bBidCollector.CollectionResult second = collector.collectMeasured(DATE, DATE);
        assertEquals(List.of("RETRY-1"), second.candidates().stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
        collector.completeCoverage(second.coverageToken(), true);

        assertEquals(2, detailAttempts.get());
        assertEquals(0, retries.size());
    }

    @Test
    void detailBudgetExhaustionStoresRemainingCandidatesForNextRun() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        InMemoryRetryRepository retries = new InMemoryRetryRepository();
        List<String> detailed = new ArrayList<>();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (isFastPath(uri)) return emptyList();
            if (path(uri).endsWith("Detail")) {
                detailed.add(query(uri).get("pblancNo"));
                return detail("<lcnsLmttList>업종코드: 6146</lcnsLmttList>");
            }
            return list(3,
                    item(common("BUDGET-1", "정보시스템 유지관리 용역")),
                    item(common("BUDGET-2", "정보시스템 유지관리 용역")),
                    item(common("BUDGET-3", "정보시스템 유지관리 용역"))
            );
        });
        D2bBidCollector collector = coverageCollector(
                transport, cursors, retries, () -> { }, 1, 1, 3
        );

        D2bBidCollector.CollectionResult first = collector.collectMeasured(DATE, DATE);
        assertEquals(BidSourceCollectionCursor.Status.PENDING,
                cursors.domesticCompetitive().status());
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(BidSourceCollectionCursor.Status.COMPLETE,
                cursors.domesticCompetitive().status());
        assertEquals(List.of("BUDGET-1"), detailed);
        assertTrue(retries.contains("2026:ORG:BUDGET-2"));
        assertTrue(retries.contains("2026:ORG:BUDGET-3"));

        D2bBidCollector.CollectionResult second = collector.collectMeasured(DATE, DATE);

        assertEquals(List.of("BUDGET-1", "BUDGET-2"), detailed);
        assertEquals(List.of("BUDGET-2"), second.candidates().stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
    }

    @Test
    void fastPathDetailConfirmationPreventsCoverageDuplicateDetailCall() {
        InMemoryCursorRepository cursors = new InMemoryCursorRepository();
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (path(uri).endsWith("Detail")) {
                return detail("<lcnsLmttList>업종코드: 6146</lcnsLmttList>");
            }
            return list(1, item(common("FAST-COVERAGE-SAME", "정보시스템 감리")));
        });
        D2bBidCollector collector = coverageCollector(transport, cursors, () -> { }, 1, 1, 1);

        D2bBidCollector.CollectionResult result = collector.collectMeasured(DATE, DATE);

        assertEquals(List.of("FAST-COVERAGE-SAME"), result.candidates().stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
        assertEquals(1, transport.detailCalls.get());
    }

    private static List<BidQualificationDto> review(
            List<BidQualificationDto> candidates,
            D2bBidCollector collector,
            Set<String> allowedCodes
    ) {
        return new G2bApiService(new BidAwardMethodClassifier(), List.of())
                .processAdditionalBidQualificationList(collector, candidates, allowedCodes);
    }

    private static D2bBidCollector coverageCollector(
            D2bBidCollector.Transport transport,
            BidSourceCollectionCursorRepository cursors,
            D2bBidCollector.CallQuota quota,
            int listBudget,
            int detailBudget,
            int pageSize
    ) {
        return coverageCollector(
                transport, cursors, new InMemoryRetryRepository(), quota,
                listBudget, detailBudget, pageSize
        );
    }

    private static D2bBidCollector coverageCollector(
            D2bBidCollector.Transport transport,
            BidSourceCollectionCursorRepository cursors,
            BidSourceCollectionRetryRepository retries,
            D2bBidCollector.CallQuota quota,
            int listBudget,
            int detailBudget,
            int pageSize
    ) {
        return new D2bBidCollector(
                "https://example.test/BidPblancInfoService", "test-key", transport, quota, true,
                cursors, retries, new BidRelevanceClassifier(), Clock.fixed(NOW, ZoneOffset.UTC),
                new D2bBidCollector.CoverageConfig(
                        true, 20, listBudget, detailBudget, pageSize
                )
        );
    }

    private static boolean isFastPath(URI uri) {
        Map<String, String> values = query(uri);
        return values.containsKey("bidNm") || values.containsKey("othbcNtatNm")
                || values.containsKey("cntrwkNm");
    }

    private static String common(String number, String title) {
        return "<demandYear>2026</demandYear><pblancNo>" + number + "</pblancNo>"
                + "<pblancOdr>1</pblancOdr><dcsNo>DC</dcsNo><bidNm>" + title + "</bidNm>"
                + "<orntCode>ORG</orntCode><ornt>기관</ornt>";
    }

    private static String item(String fields) {
        return "<item>" + fields + "</item>";
    }

    private static String emptyList() {
        return list(0);
    }

    private static String list(int total, String... items) {
        return "<response><header><resultCode>00</resultCode></header><body><totalCount>" + total
                + "</totalCount><items>" + String.join("", items) + "</items></body></response>";
    }

    private static String detail(String fields) {
        return "<response><header><resultCode>00</resultCode></header><body><item>"
                + fields + "</item></body></response>";
    }

    private static String path(URI uri) {
        String value = uri.getPath();
        return value.substring(value.lastIndexOf('/') + 1);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class RecordingTransport implements D2bBidCollector.Transport {
        private final Function<URI, String> response;
        private final List<URI> uris = new ArrayList<>();
        private final AtomicInteger detailCalls = new AtomicInteger();

        private RecordingTransport(Function<URI, String> response) {
            this.response = response;
        }

        @Override
        public String get(URI uri) {
            uris.add(uri);
            if (path(uri).endsWith("Detail")) detailCalls.incrementAndGet();
            return response.apply(uri);
        }
    }

    private static final class InMemoryCursorRepository implements BidSourceCollectionCursorRepository {
        private final Map<String, BidSourceCollectionCursor> values = new LinkedHashMap<>();
        private long sequence;

        @Override
        public BidSourceCollectionCursor getOrCreate(
                String sourceCode,
                String scope,
                String operation,
                LocalDate windowStart,
                LocalDate windowEnd,
                int pageSize,
                Instant now
        ) {
            String key = sourceCode + ':' + scope + ':' + operation + ':' + windowStart + ':' + windowEnd;
            return values.computeIfAbsent(key, ignored -> new BidSourceCollectionCursor(
                    ++sequence, sourceCode, scope, operation, windowStart, windowEnd,
                    1, pageSize, null, BidSourceCollectionCursor.Status.PENDING, 0, now, now
            ));
        }

        @Override
        public List<BidSourceCollectionCursor> findIncomplete(String sourceCode, String scope) {
            return values.values().stream()
                    .filter(value -> value.sourceCode().equals(sourceCode) && value.scope().equals(scope))
                    .filter(value -> value.status() != BidSourceCollectionCursor.Status.COMPLETE)
                    .sorted(java.util.Comparator.comparing(BidSourceCollectionCursor::windowEnd).reversed()
                            .thenComparing(BidSourceCollectionCursor::operation))
                    .toList();
        }

        @Override
        public boolean reopenCompleted(long id, long expectedVersion, Instant updatedAt) {
            Map.Entry<String, BidSourceCollectionCursor> entry = values.entrySet().stream()
                    .filter(candidate -> candidate.getValue().id() == id).findFirst().orElseThrow();
            BidSourceCollectionCursor current = entry.getValue();
            if (current.version() != expectedVersion
                    || current.status() != BidSourceCollectionCursor.Status.COMPLETE) return false;
            entry.setValue(new BidSourceCollectionCursor(
                    current.id(), current.sourceCode(), current.scope(), current.operation(),
                    current.windowStart(), current.windowEnd(), 1, current.pageSize(), null,
                    BidSourceCollectionCursor.Status.PENDING, current.version() + 1,
                    current.createdAt(), updatedAt
            ));
            return true;
        }

        @Override
        public boolean advance(
                long id,
                long expectedVersion,
                int nextPage,
                Integer totalCountSnapshot,
                BidSourceCollectionCursor.Status status,
                Instant updatedAt
        ) {
            Map.Entry<String, BidSourceCollectionCursor> entry = values.entrySet().stream()
                    .filter(candidate -> candidate.getValue().id() == id).findFirst().orElseThrow();
            BidSourceCollectionCursor current = entry.getValue();
            if (current.version() != expectedVersion) return false;
            entry.setValue(new BidSourceCollectionCursor(
                    current.id(), current.sourceCode(), current.scope(), current.operation(),
                    current.windowStart(), current.windowEnd(), nextPage, current.pageSize(),
                    totalCountSnapshot, status, current.version() + 1, current.createdAt(), updatedAt
            ));
            return true;
        }

        private BidSourceCollectionCursor domesticCompetitive() {
            return values.values().stream()
                    .filter(value -> value.operation().equals("DOMESTIC_COMPETITIVE"))
                    .findFirst().orElseThrow();
        }
    }

    private static final class InMemoryRetryRepository implements BidSourceCollectionRetryRepository {
        private final Map<String, BidSourceCollectionRetry> values = new LinkedHashMap<>();
        private long sequence;

        @Override
        public BidSourceCollectionRetry savePending(
                String sourceCode,
                String scope,
                String operation,
                LocalDate windowStart,
                LocalDate windowEnd,
                int pageNumber,
                String sourceNoticeId,
                String revision,
                String payload,
                Instant now
        ) {
            String normalizedRevision = revision == null ? "" : revision;
            String key = sourceCode + ':' + scope + ':' + operation + ':' + windowStart + ':'
                    + windowEnd + ':' + sourceNoticeId + ':' + normalizedRevision;
            BidSourceCollectionRetry current = values.get(key);
            BidSourceCollectionRetry saved = new BidSourceCollectionRetry(
                    current == null ? ++sequence : current.id(), sourceCode, scope, operation,
                    windowStart, windowEnd, pageNumber, sourceNoticeId, normalizedRevision, payload,
                    current == null ? 0 : current.retryCount(),
                    current == null ? null : current.lastErrorCode(),
                    current == null ? null : current.lastAttemptAt(),
                    current == null ? now : current.createdAt(), now
            );
            values.put(key, saved);
            return saved;
        }

        @Override
        public List<BidSourceCollectionRetry> findPending(String sourceCode, String scope) {
            return values.values().stream()
                    .filter(value -> value.sourceCode().equals(sourceCode) && value.scope().equals(scope))
                    .toList();
        }

        @Override
        public void recordFailure(long id, String errorCode, Instant attemptedAt) {
            Map.Entry<String, BidSourceCollectionRetry> entry = values.entrySet().stream()
                    .filter(candidate -> candidate.getValue().id() == id).findFirst().orElseThrow();
            BidSourceCollectionRetry current = entry.getValue();
            entry.setValue(new BidSourceCollectionRetry(
                    current.id(), current.sourceCode(), current.scope(), current.operation(),
                    current.windowStart(), current.windowEnd(), current.pageNumber(),
                    current.sourceNoticeId(), current.revision(), current.payload(),
                    current.retryCount() + 1, errorCode, attemptedAt,
                    current.createdAt(), attemptedAt
            ));
        }

        @Override
        public void delete(long id) {
            values.entrySet().removeIf(entry -> entry.getValue().id() == id);
        }

        private int size() {
            return values.size();
        }

        private boolean contains(String sourceNoticeId) {
            return values.values().stream()
                    .anyMatch(value -> value.sourceNoticeId().equals(sourceNoticeId));
        }
    }
}
