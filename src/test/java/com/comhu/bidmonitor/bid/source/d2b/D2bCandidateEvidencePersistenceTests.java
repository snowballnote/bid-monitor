package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.persistence.*;
import com.comhu.bidmonitor.bid.persistence.jdbc.*;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.bid.source.d2b.promotion.*;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.classifier.BidRelevance;
import com.comhu.bidmonitor.classifier.BidRelevanceClassifier;
import com.comhu.bidmonitor.service.G2bApiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class D2bCandidateEvidencePersistenceTests {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;
    private JdbcBidSourceCandidateRepository candidates;
    private JdbcBidSourceCollectionCursorRepository cursors;
    private JdbcBidSourceCollectionRetryRepository retries;
    private D2bPromotionEvaluator evaluator;
    private DataSourceTransactionManager transactions;
    private final AtomicBoolean failSave = new AtomicBoolean();
    private D2bCandidateEvidenceService service;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:candidate-" + UUID.randomUUID(), "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        candidates = new JdbcBidSourceCandidateRepository(jdbc);
        cursors = new JdbcBidSourceCollectionCursorRepository(jdbc);
        retries = new JdbcBidSourceCollectionRetryRepository(jdbc);
        evaluator = new D2bPromotionEvaluator(new AllowedIndustryCodeProvider(new AllowedIndustryCodeRepository() {
            public Set<String> findAll() { return Set.of("6525"); }
            public Set<String> replaceAll(Set<String> codes) { throw new UnsupportedOperationException(); }
        }));
        BidSourceCandidateRepository failingRepository = new BidSourceCandidateRepository() {
            public void insert(BidSourceCandidate candidate) { candidates.insert(candidate); }
            public void save(BidSourceCandidate candidate) {
                candidates.save(candidate);
                if (failSave.get()) throw new IllegalStateException("fixture storage failure");
            }
            public Optional<BidSourceCandidate> findByIdentity(String source, String id, String revision) {
                return candidates.findByIdentity(source, id, revision);
            }
        };
        service = new D2bCandidateEvidenceService(failingRepository, evaluator,
                new BidRelevanceClassifier(), transactions, CLOCK);
    }

    @AfterEach
    void closeDatabase() { dataSource.destroy(); }

    @Test
    void preservesPublicRawQualificationAndUnverifiedParsingWithoutCredentials() {
        D2bBidCollector collector = collector(uri -> {
            if (fast(uri)) return list(0, "");
            if (detailRequest(uri)) return detail("""
                    <lcnsLmttList>6525 또는 9999</lcnsLmttList>
                    <industClList>업종 1234</industClList><areaLmttList>서울</areaLmttList>
                    <serviceKey>secret-value</serviceKey><Authorization>Bearer header-secret</Authorization>
                    <detailUrl>https://example.test?serviceKey=url-secret</detailUrl>
                    <ornt>https://example.test?serviceKey=embedded-secret</ornt>
                    <budgetAmount>fixture-key</budgetAmount>
                    """);
            return list(1, item("RAW", "건축 감리"));
        });
        var result = collector.collectMeasured(DATE, DATE);
        BidSourceCandidate saved = candidate("RAW");
        assertEquals("INCOMPLETE", saved.qualificationState());
        assertEquals("REVIEW", saved.promotionState());
        assertTrue(saved.detailFields().contains("6525 또는 9999"));
        assertTrue(saved.detailFields().contains("업종 1234"));
        assertTrue(saved.detailFields().contains("서울"));
        assertTrue(saved.parsedQualification().contains("UNVERIFIED"));
        assertTrue(saved.parsedQualification().contains("6525"));
        assertEquals("[\"6525\"]", saved.allowedCodes());
        assertEquals(64, saved.allowedCodesHash().length());
        String stored = saved.toString();
        for (String forbidden : List.of("secret-value", "header-secret", "url-secret", "embedded-secret", "fixture-key", "https://")) {
            assertFalse(stored.contains(forbidden), forbidden);
        }
        assertEquals(0, count("bid_notice"));
        assertEquals(1, cursor().nextPage());
        collector.completeCoverage(result.coverageToken(), true);
        assertEquals(0, cursors.findIncomplete("D2B", "FULL_DATE").stream()
                .filter(c -> c.operation().equals("DOMESTIC_COMPETITIVE")).count());
    }

    @Test
    void zeroMainCandidatePageStillPersistsReviewEvidence() {
        D2bBidCollector collector = collector(uri -> fast(uri) ? list(0, "")
                : detailRequest(uri) ? detail("") : list(1, item("ABSENT", "건축 감리")));
        var result = collector.collectMeasured(DATE, DATE);
        var main = new G2bApiService(new BidAwardMethodClassifier(), List.of())
                .processAdditionalBidQualificationList(collector, result.candidates(), Set.of("6525"));
        assertTrue(main.isEmpty());
        assertEquals("ABSENT", candidate("ABSENT").qualificationState());
        assertEquals("REVIEW", candidate("ABSENT").promotionState());
        collector.completeCoverage(result.coverageToken(), true);
        assertTrue(cursors.findIncomplete("D2B", "FULL_DATE").stream()
                .noneMatch(c -> c.operation().equals("DOMESTIC_COMPETITIVE")));
    }

    @Test
    void missingItemIsDurableRetryAndSuccessDoesNotDeleteUntilPersistenceCallback() {
        AtomicBoolean missing = new AtomicBoolean(true);
        D2bBidCollector collector = collector(uri -> fast(uri) ? list(0, "")
                : detailRequest(uri) ? (missing.get() ? list(0, "") : detail(""))
                : list(1, item("RETRY", "건축 감리")));
        var first = collector.collectMeasured(DATE, DATE);
        assertEquals("ITEM_MISSING", candidate("RETRY").detailStatus());
        assertNull(candidate("RETRY").qualificationState());
        assertNull(candidate("RETRY").promotionState());
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(1, retries.findPending("D2B", "FULL_DATE").size());
        missing.set(false);
        var second = collector.collectMeasured(DATE, DATE);
        assertEquals("AVAILABLE", candidate("RETRY").detailStatus());
        assertEquals(1, retries.findPending("D2B", "FULL_DATE").size());
        collector.completeCoverage(second.coverageToken(), true);
        assertTrue(retries.findPending("D2B", "FULL_DATE").isEmpty());
        assertEquals(1, count("bid_source_candidate"));
    }

    @Test
    void candidateSaveFailureRollsBackAndKeepsZeroMainPageCursor() {
        D2bBidCollector collector = collector(uri -> fast(uri) ? list(0, "")
                : detailRequest(uri) ? detail("") : list(1, item("ROLLBACK", "건축 감리")));
        failSave.set(true);
        var failure = assertThrows(D2bBidCollector.CollectionException.class,
                () -> collector.collectMeasured(DATE, DATE));
        assertEquals("D2B_CANDIDATE_PERSISTENCE", failure.getErrorCode());
        assertEquals(0, count("bid_source_candidate"));
        assertEquals(1, cursor().nextPage());
        assertEquals(BidSourceCollectionCursor.Status.PENDING, cursor().status());
        failSave.set(false);
        var retry = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(retry.coverageToken(), true);
        assertEquals(1, count("bid_source_candidate"));
    }

    @Test
    void retryDetailSuccessFollowedByCandidateFailureRetainsRetryAndOldEvidence() {
        AtomicBoolean missing = new AtomicBoolean(true);
        D2bBidCollector collector = collector(uri -> fast(uri) ? list(0, "")
                : detailRequest(uri) ? (missing.get() ? list(0, "") : detail(""))
                : list(1, item("KEEP-RETRY", "정보시스템 감리")));
        var first = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(first.coverageToken(), true);
        missing.set(false);
        failSave.set(true);
        assertThrows(D2bBidCollector.CollectionException.class, () -> collector.collectMeasured(DATE, DATE));
        assertEquals(1, retries.findPending("D2B", "FULL_DATE").size());
        assertEquals("ITEM_MISSING", candidate("KEEP-RETRY").detailStatus());
        assertEquals(1, cursor().nextPage());
        failSave.set(false);
        var third = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(third.coverageToken(), true);
        assertEquals("MAIN", candidate("KEEP-RETRY").promotionState());
        assertEquals(1, count("bid_source_candidate"));
        assertTrue(retries.findPending("D2B", "FULL_DATE").isEmpty());
    }

    @Test
    void sameIdentityFromFastAndCoverageHasOneEvidenceRowAndOneDetailCall() {
        AtomicInteger details = new AtomicInteger();
        D2bBidCollector collector = collector(uri -> {
            if (detailRequest(uri)) { details.incrementAndGet(); return detail(""); }
            return list(1, item("SHARED", "정보시스템 감리"));
        });
        var first = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(first.coverageToken(), false);
        assertEquals(1, details.get());
        assertEquals("[\"COVERAGE\",\"FAST\"]", candidate("SHARED").discoveryPaths());
        assertEquals(1, count("bid_source_candidate"));
        var second = collector.collectMeasured(DATE, DATE);
        collector.completeCoverage(second.coverageToken(), true);
        assertEquals(1, count("bid_source_candidate"));
        assertEquals(NOW, candidate("SHARED").firstSeenAt());
    }

    @Test
    void budgetPendingIsStoredSeparatelyFromAbsenceAndResumes() {
        D2bBidCollector collector = collector(uri -> fast(uri) ? list(0, "")
                : detailRequest(uri) ? detail("")
                : list(2, item("ONE", "건축 감리") + item("TWO", "건축 감리")), 2);
        var first = collector.collectMeasured(DATE, DATE);
        assertEquals("PENDING", candidate("TWO").detailStatus());
        assertNull(candidate("TWO").qualificationState());
        collector.completeCoverage(first.coverageToken(), true);
        assertEquals(1, retries.findPending("D2B", "FULL_DATE").size());
        var second = collector.collectMeasured(DATE, DATE);
        assertEquals("ABSENT", candidate("TWO").qualificationState());
        collector.completeCoverage(second.coverageToken(), true);
    }

    @ParameterizedTest
    @CsvSource({"MATCH,MAIN", "MISMATCH,ARCHIVED", "INCOMPLETE,REVIEW", "ABSENT,REVIEW"})
    void repositoryRoundTripsAllQualificationAndPromotionStates(String state, String promotion) {
        // Verified group fixtures exercise the Phase 1 contract. The live adapter NEVER certifies it.
        var evidence = switch (state) {
            case "MATCH", "MISMATCH" -> new D2bQualificationEvidence(
                    D2bQualificationEvidence.DetailStatus.AVAILABLE, "fixture verified qualification", "", "",
                    D2bQualificationEvidence.ParseCompleteness.VERIFIED_COMPLETE,
                    List.of(List.of(state.equals("MATCH") ? "6525" : "9999")));
            case "ABSENT" -> D2bQualificationEvidence.rawDetail("", "", "");
            default -> D2bQualificationEvidence.rawDetail("6525 또는 미해석 대안", "", "");
        };
        var decision = evaluator.evaluate(evidence, BidRelevance.IRRELEVANT);
        BidSourceCandidate saved = new BidSourceCandidate("D2B", "STATE-" + state, "",
                "[\"FIXTURE\"]", "[\"COVERAGE\"]", "건축 감리", "{}", "{}", "{}", "AVAILABLE",
                decision.qualification().orElseThrow().name(), "IRRELEVANT",
                decision.promotion().orElseThrow().name(), decision.reason().name(), "[\"6525\"]",
                "a".repeat(64), "fixture-v1", "d2b-promotion-v1", NOW, NOW);
        candidates.save(saved);
        candidates.save(saved);
        assertEquals(promotion, candidates.findByIdentity("D2B", "STATE-" + state, "").orElseThrow().promotionState());
        assertEquals(1, count("bid_source_candidate"));
    }

    private D2bBidCollector collector(Function<URI, String> responses) { return collector(responses, 1); }

    private D2bBidCollector collector(Function<URI, String> responses, int pageSize) {
        return new D2bBidCollector("https://example.test", "fixture-key", responses::apply, () -> { }, true,
                cursors, retries, new BidRelevanceClassifier(), CLOCK,
                new D2bBidCollector.CoverageConfig(true, 40, 1, 1, pageSize), service::saveAll);
    }

    private BidSourceCandidate candidate(String no) {
        return candidates.findByIdentity("D2B", "2026:ORG:" + no, "1").orElseThrow();
    }
    private BidSourceCollectionCursor cursor() {
        return cursors.findIncomplete("D2B", "FULL_DATE").stream()
                .filter(c -> c.operation().equals("DOMESTIC_COMPETITIVE")).findFirst().orElseThrow();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static boolean fast(URI uri) {
        return uri.getRawQuery().matches(".*[&](bidNm|othbcNtatNm|cntrwkNm)=.*");
    }
    private static boolean detailRequest(URI uri) { return uri.getPath().endsWith("Detail"); }
    private static String item(String number, String title) {
        return "<item><demandYear>2026</demandYear><pblancYear>2026</pblancYear><pblancNo>" + number
                + "</pblancNo><pblancOdr>1</pblancOdr><orntCode>ORG</orntCode><dcsNo>DC</dcsNo>"
                + "<iemNo>I</iemNo><cntrwkNo>C</cntrwkNo><ntatPlanDate>20261015</ntatPlanDate>"
                + "<pblancSeCode>1</pblancSeCode><bidNm>" + title + "</bidNm></item>";
    }
    private static String list(int total, String items) {
        return "<response><header><resultCode>00</resultCode></header><body><totalCount>" + total
                + "</totalCount><items>" + items + "</items></body></response>";
    }
    private static String detail(String fields) {
        return "<response><header><resultCode>00</resultCode></header><body><item>"
                + fields + "</item></body></response>";
    }
}
