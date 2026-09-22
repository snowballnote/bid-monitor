package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidNoticeSaveResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class JdbcBidPersistenceTests {

    private static final Instant FIRST_SEEN = Instant.parse("2026-09-22T01:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BidNoticeRepository noticeRepository;

    @Autowired
    private BidCollectionRunRepository runRepository;

    @Autowired
    private BidSourceStateRepository stateRepository;

    @BeforeEach
    void clearBidPersistenceTables() {
        jdbcTemplate.update("DELETE FROM bid_notice_version");
        jdbcTemplate.update("DELETE FROM bid_notice");
        jdbcTemplate.update("DELETE FROM bid_collection_run");
        jdbcTemplate.update("DELETE FROM bid_source_state");
    }

    @Test
    void savesNewNoticeAndRestoresStructuredStatus() {
        BidNoticeSaveResult result = noticeRepository.save(notice("G2B", "20260922001", "000", 'a'));

        assertEquals(BidNoticeSaveResult.ChangeType.NEW, result.changeType());
        BidNotice saved = result.notice();
        assertNotNull(saved.getId());
        assertEquals("G2B", saved.getSourceCode());
        assertEquals("20260922001", saved.getSourceNoticeId());
        assertEquals("000", saved.getRevisionKey());
        assertEquals("취소", saved.getNoticeStatus());
        assertEquals("CANCELLED", saved.getNoticeStatusCode());
        assertEquals(hash('a'), saved.getContentHash());
    }

    @Test
    void repeatedIdenticalNoticeUpdatesOnlyLastSeenAndKeepsFirstSeen() {
        BidNotice first = noticeRepository.save(notice("D2B", "2026:1:77", "1", 'b')).notice();
        Instant later = FIRST_SEEN.plusSeconds(3600);

        BidNoticeSaveResult repeated = noticeRepository.save(
                notice("D2B", "2026:1:77", "1", 'b').toBuilder()
                        .firstSeenAt(later)
                        .lastSeenAt(later)
                        .build()
        );

        assertEquals(BidNoticeSaveResult.ChangeType.UNCHANGED, repeated.changeType());
        assertEquals(first.getId(), repeated.notice().getId());
        assertEquals(FIRST_SEEN, repeated.notice().getFirstSeenAt());
        assertEquals(later, repeated.notice().getLastSeenAt());
        assertEquals(1, noticeRepository.findAll().size());
        assertTrue(noticeRepository.findVersions(first.getId()).isEmpty());
    }

    @Test
    void distinguishesSameNoticeIdFromDifferentSources() {
        BidNotice g2b = noticeRepository.save(notice("G2B", "shared-1", null, 'c')).notice();
        BidNotice d2b = noticeRepository.save(notice("D2B", "shared-1", null, 'd')).notice();

        assertNotEquals(g2b.getId(), d2b.getId());
        assertEquals(2, noticeRepository.findAll().size());
    }

    @Test
    void normalizesMissingRevisionAndPreventsDuplicateIdentity() {
        BidNotice first = noticeRepository.save(notice("G2B", "no-revision", null, 'e')).notice();
        BidNoticeSaveResult repeated = noticeRepository.save(notice("G2B", "no-revision", " ", 'e'));

        assertEquals("", first.getRevisionKey());
        assertEquals(first.getId(), repeated.notice().getId());
        assertEquals(1, noticeRepository.findAll().size());
        assertTrue(noticeRepository.findByIdentity("G2B", "no-revision", null).isPresent());
    }

    @Test
    void storesDifferentRevisionsAsSeparateNotices() {
        BidNotice first = noticeRepository.save(notice("D2B", "2026:1:88", "1", 'f')).notice();
        BidNotice second = noticeRepository.save(notice("D2B", "2026:1:88", "2", '6')).notice();

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, noticeRepository.findAll().size());
    }

    @Test
    void changingContentWithinRevisionPreservesPreviousSnapshot() {
        BidNotice original = noticeRepository.save(notice("EXPRESSWAY", "notice-9", "3", '1')).notice();
        Instant changedAt = FIRST_SEEN.plusSeconds(7200);
        BidNotice changed = notice("EXPRESSWAY", "notice-9", "3", '2').toBuilder()
                .title("정정된 정보시스템 감리 공고")
                .noticeStatus("정정")
                .noticeStatusCode("CORRECTED")
                .firstSeenAt(changedAt)
                .lastSeenAt(changedAt)
                .build();

        BidNoticeSaveResult result = noticeRepository.save(changed);

        assertEquals(BidNoticeSaveResult.ChangeType.UPDATED, result.changeType());
        assertEquals(hash('1'), result.previousContentHash());
        assertEquals(original.getId(), result.notice().getId());
        assertEquals(FIRST_SEEN, result.notice().getFirstSeenAt());
        assertEquals(changedAt, result.notice().getLastSeenAt());
        assertEquals(hash('2'), result.notice().getContentHash());
        assertEquals("CORRECTED", result.notice().getNoticeStatusCode());
        var versions = noticeRepository.findVersions(original.getId());
        assertEquals(1, versions.size());
        assertEquals(original.getTitle(), versions.getFirst().getTitle());
        assertEquals(hash('1'), versions.getFirst().getContentHash());
        assertEquals("CANCELLED", versions.getFirst().getNoticeStatusCode());
    }

    @Test
    void rollsBackVersionWhenCurrentUpdateFails() {
        BidNotice original = noticeRepository.save(notice("G2B", "rollback-1", "000", '3')).notice();
        BidNotice invalidUpdate = notice("G2B", "rollback-1", "000", '4').toBuilder()
                .title("트랜잭션 실패 확인")
                .detailUrl("x".repeat(4001))
                .lastSeenAt(FIRST_SEEN.plusSeconds(60))
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> noticeRepository.save(invalidUpdate));

        BidNotice persisted = noticeRepository.findById(original.getId()).orElseThrow();
        assertEquals(original.getTitle(), persisted.getTitle());
        assertEquals(hash('3'), persisted.getContentHash());
        assertTrue(noticeRepository.findVersions(original.getId()).isEmpty());
    }

    @Test
    void savesAndQueriesCollectionRun() {
        BidCollectionRun run = BidCollectionRun.builder()
                .sourceCode("D2B")
                .triggerType(BidCollectionRun.TriggerType.MANUAL)
                .queryStartDate(LocalDate.of(2026, 9, 21))
                .queryEndDate(LocalDate.of(2026, 9, 22))
                .startedAt(FIRST_SEEN)
                .status(BidCollectionRun.Status.RUNNING)
                .build();

        BidCollectionRun saved = runRepository.save(run);
        BidCollectionRun completed = runRepository.update(saved.toBuilder()
                .finishedAt(FIRST_SEEN.plusSeconds(30))
                .status(BidCollectionRun.Status.PARTIAL)
                .apiCallCount(37)
                .collectedCount(8)
                .newCount(5)
                .changedCount(2)
                .failureCount(1)
                .errorCode("DETAIL_PARTIAL_FAILURE")
                .build());

        assertNotNull(completed.getId());
        assertEquals(37, completed.getApiCallCount());
        assertEquals("DETAIL_PARTIAL_FAILURE", completed.getErrorCode());
        assertEquals(List.of(completed), runRepository.findBySourceCodeLatestFirst("D2B"));
    }

    @Test
    void upsertsSourceStateIncludingDailyQuotaAndCooldown() {
        BidSourceState initial = sourceState(20, 100);
        BidSourceState saved = stateRepository.save(initial);

        BidSourceState updated = stateRepository.save(saved.toBuilder()
                .usedCalls(46)
                .consecutiveFailures(1)
                .lastFailureAt(FIRST_SEEN.plusSeconds(120))
                .lastErrorCode("D2B_RATE_LIMIT")
                .build());

        assertEquals(1, stateRepository.findAll().size());
        assertEquals(100, updated.getDailyLimit());
        assertEquals(46, updated.getUsedCalls());
        assertEquals(LocalDate.of(2026, 9, 22), updated.getQuotaDate());
        assertEquals(1800, updated.getCooldownSeconds());
        assertEquals("D2B_RATE_LIMIT", updated.getLastErrorCode());
    }

    @Test
    void initializesBidTablesTogetherWithExistingSchema() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND TABLE_NAME IN ('EXTERNAL_NOTICE', 'NOTIFICATION_DELIVERY', 'BID_NOTICE',
                    'BID_NOTICE_VERSION', 'BID_COLLECTION_RUN', 'BID_SOURCE_STATE')
                """, String.class);

        assertEquals(6, tables.size());
    }

    private BidNotice notice(String sourceCode, String sourceNoticeId, String revision, char hashCharacter) {
        return BidNotice.builder()
                .sourceCode(sourceCode)
                .sourceNoticeId(sourceNoticeId)
                .revisionKey(revision)
                .noticeNumber("2026-001")
                .title("정보시스템 감리 공고")
                .orderingOrganization("테스트 기관")
                .publishedAt(LocalDateTime.of(2026, 9, 22, 9, 0))
                .submissionDeadlineAt(LocalDateTime.of(2026, 9, 30, 18, 0))
                .bidOpeningAt(LocalDateTime.of(2026, 10, 1, 10, 0))
                .contractMethod("제한경쟁")
                .bidMethod("전자입찰")
                .noticeStatus("취소")
                .noticeStatusCode("CANCELLED")
                .detailUrl("https://example.test/bids/2026-001")
                .relevant(true)
                .analysisStatus("MATCHED")
                .analysisResult("{\"reason\":\"정보시스템 감리\"}")
                .contentHash(hash(hashCharacter))
                .firstSeenAt(FIRST_SEEN)
                .lastSeenAt(FIRST_SEEN)
                .build();
    }

    private BidSourceState sourceState(int usedCalls, int dailyLimit) {
        return BidSourceState.builder()
                .sourceCode("D2B")
                .lastAttemptAt(FIRST_SEEN)
                .lastSuccessAt(FIRST_SEEN)
                .consecutiveFailures(0)
                .nextRunAt(FIRST_SEEN.plusSeconds(86400))
                .dailyLimit(dailyLimit)
                .usedCalls(usedCalls)
                .quotaDate(LocalDate.of(2026, 9, 22))
                .cooldownUntil(FIRST_SEEN.plusSeconds(1800))
                .cooldownSeconds(1800)
                .build();
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
