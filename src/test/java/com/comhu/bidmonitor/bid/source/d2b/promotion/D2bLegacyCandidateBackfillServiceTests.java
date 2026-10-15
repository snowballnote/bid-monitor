package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.persistence.*;
import com.comhu.bidmonitor.bid.persistence.jdbc.JdbcBidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.jdbc.JdbcBidSourceCandidateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class D2bLegacyCandidateBackfillServiceTests {
    private static final Instant SEEN = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(SEEN.plusSeconds(100), ZoneOffset.UTC);
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcBidNoticeRepository notices;
    private JdbcBidSourceCandidateRepository candidates;
    private D2bLegacyCandidateBackfillService service;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:legacy-" + UUID.randomUUID(), "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        notices = new JdbcBidNoticeRepository(jdbc);
        candidates = new JdbcBidSourceCandidateRepository(jdbc);
        service = service(candidates);
    }

    @AfterEach
    void close() { dataSource.destroy(); }

    @Test
    void legacyEvidenceCannotCertifyQualificationOrInventHistoricalAllowedCodes() {
        BidNotice notice = notice("D2B", "ONE", "1", "추가확인필요");
        var result = service.backfillBatch(0, 10);
        assertEquals(1, result.inserted());
        var saved = candidate(notice);
        assertEquals("PENDING", saved.detailStatus());
        assertNull(saved.qualificationState());
        assertNull(saved.promotionState());
        assertEquals("UNKNOWN", saved.titleRelevance());
        assertEquals("null", saved.allowedCodes());
        assertEquals("", saved.allowedCodesHash());
        assertTrue(saved.parsedQualification().contains("LEGACY"));
        assertTrue(saved.parsedQualification().contains("UNVERIFIED"));
        assertTrue(saved.parsedQualification().contains("추가확인필요"));
        assertFalse(saved.parsedQualification().contains("VERIFIED_COMPLETE"));
        assertEquals("{}", saved.detailFields());
        assertEquals(SEEN, saved.firstSeenAt());
        assertTrue(saved.listFields().contains("\"legacyNoticeId\":" + notice.getId()));
    }

    @Test
    void repeatedBatchesPreserveIdentityRevisionsAndExistingCandidateExactly() {
        BidNotice first = notice("D2B", "SAME", "", "MATCH");
        BidNotice second = notice("D2B", "SAME", "2", "EXCLUDED");
        var firstBatch = service.backfillBatch(0, 1);
        var snapshot = candidate(first);
        var secondBatch = service.backfillBatch(firstBatch.lastNoticeId(), 1);
        assertEquals(second.getId(), secondBatch.lastNoticeId());
        assertEquals(0, service.backfillBatch(secondBatch.lastNoticeId(), 1).scanned());
        var rerun = service.backfillBatch(0, 10);
        assertEquals(0, rerun.inserted());
        assertEquals(2, rerun.skipped());
        assertEquals(snapshot, candidate(first));
        assertNull(candidate(second).qualificationState());
        assertEquals(2, count("bid_source_candidate"));
    }

    @Test
    void freshDetailEvidenceIsNeverOverwritten() {
        BidNotice notice = notice("D2B", "FRESH", "1", "추가확인필요");
        var fresh = fresh(notice);
        candidates.save(fresh);
        assertEquals(1, service.backfillBatch(0, 10).skipped());
        assertEquals(fresh, candidate(notice));
        // Insert-only protection also handles a race after the existence check.
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> candidates.insert(fresh));
        assertEquals(fresh, candidate(notice));
    }

    @Test
    void midBatchFailureRollsBackOnlyCurrentBatchAndCanResume() {
        BidNotice first = notice("D2B", "FIRST", "1", "추가확인필요");
        notice("D2B", "SECOND", "1", "추가확인필요");
        notice("D2B", "THIRD", "1", "추가확인필요");
        var committed = service.backfillBatch(0, 1);
        var before = candidate(first);
        AtomicInteger attempts = new AtomicInteger();
        BidSourceCandidateRepository failing = new BidSourceCandidateRepository() {
            public void save(BidSourceCandidate c) { fail("Backfill must not upsert"); }
            public void insert(BidSourceCandidate c) {
                candidates.insert(c);
                if (attempts.incrementAndGet() == 2) throw new IllegalStateException("fixture failure");
            }
            public Optional<BidSourceCandidate> findByIdentity(String source, String id, String revision) {
                return candidates.findByIdentity(source, id, revision);
            }
        };
        assertThrows(IllegalStateException.class,
                () -> service(failing).backfillBatch(committed.lastNoticeId(), 10));
        assertEquals(1, count("bid_source_candidate"));
        assertEquals(before, candidate(first));
        assertEquals(2, service.backfillBatch(committed.lastNoticeId(), 10).inserted());
        assertEquals(0, service.backfillBatch(0, 10).inserted());
    }

    @Test
    void noticesVersionsRunsAndOtherSourcesAreUnchanged() {
        BidNotice d2b = notice("D2B", "HISTORY", "1", "추가확인필요");
        notices.save(d2b.toBuilder().title("updated title").contentHash("b".repeat(64)).build());
        for (String source : List.of("G2B", "LH", "KOGAS", "KWATER", "KOREA_EXPRESSWAY")) {
            notice(source, "OTHER", "1", "추가확인필요");
        }
        jdbc.update("""
                INSERT INTO bid_collection_run
                (id,source_code,trigger_type,query_start_date,query_end_date,started_at,status)
                VALUES (1,'D2B','MANUAL',DATE '2026-10-01',DATE '2026-10-01',TIMESTAMP '2026-10-01 00:00:00','SUCCESS')
                """);
        notices.linkToRun(1, d2b.getId());
        var beforeNotices = notices.findAll();
        var beforeVersions = notices.findVersions(d2b.getId());
        var beforeRuns = jdbc.queryForList("SELECT * FROM bid_collection_run");
        var beforeLinks = jdbc.queryForList("SELECT * FROM bid_collection_run_notice");
        assertEquals(1, service.backfillBatch(0, 10).inserted());
        assertEquals(beforeNotices, notices.findAll());
        assertEquals(1, beforeVersions.size());
        assertEquals(beforeVersions, notices.findVersions(d2b.getId()));
        assertEquals(beforeRuns, jdbc.queryForList("SELECT * FROM bid_collection_run"));
        assertEquals(beforeLinks, jdbc.queryForList("SELECT * FROM bid_collection_run_notice"));
        assertEquals(1, count("bid_source_candidate"));
    }

    @Test
    void credentialsAndUrlsInLegacyAnalysisAreNotCopied() {
        BidNotice notice = notice("D2B", "SAFE", "1", "추가확인필요");
        notices.save(notice.toBuilder().analysisResult("https://example.test?serviceKey=secret")
                .detailUrl("https://example.test?serviceKey=secret").build());
        service.backfillBatch(0, 10);
        assertFalse(candidate(notice).toString().contains("secret"));
        assertFalse(candidate(notice).toString().contains("https://"));
    }

    @Test
    void invalidBatchArgumentsMakeNoChanges() {
        assertThrows(IllegalArgumentException.class, () -> service.backfillBatch(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.backfillBatch(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.backfillBatch(0, 1001));
        assertEquals(0, count("bid_source_candidate"));
    }

    private D2bLegacyCandidateBackfillService service(BidSourceCandidateRepository repository) {
        return new D2bLegacyCandidateBackfillService(jdbc, notices, repository,
                new DataSourceTransactionManager(dataSource), CLOCK);
    }

    private BidNotice notice(String source, String id, String revision, String status) {
        return notices.save(BidNotice.builder().sourceCode(source).sourceNoticeId(id).revisionKey(revision)
                .title("냉장고 구매").relevant(true).analysisStatus(status).analysisResult("legacy analysis")
                .contentHash("a".repeat(64)).firstSeenAt(SEEN).lastSeenAt(SEEN).build()).notice();
    }

    private BidSourceCandidate candidate(BidNotice n) {
        return candidates.findByIdentity(n.getSourceCode(), n.getSourceNoticeId(), n.getRevisionKey()).orElseThrow();
    }

    private BidSourceCandidate fresh(BidNotice n) {
        return new BidSourceCandidate("D2B", n.getSourceNoticeId(), n.getRevisionKey(), "[]", "[\"COVERAGE\"]",
                n.getTitle(), "{}", "{\"lcnsLmttList\":\"6525\"}", "{\"completeness\":\"UNVERIFIED\"}",
                "AVAILABLE", "INCOMPLETE", "IRRELEVANT", "REVIEW", "RELATIONSHIP_UNVERIFIED",
                "[\"6525\"]", "a".repeat(64), "parser-v1", "policy-v1", SEEN, SEEN);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
