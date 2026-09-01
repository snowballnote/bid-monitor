package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.externalnotice.change.ExternalNoticeChangeService;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.classifier.PiaNoticeClassifier;
import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollectionException;
import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollector;
import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import com.comhu.bidmonitor.externalnotice.domain.NoticeSource;
import com.comhu.bidmonitor.externalnotice.fingerprint.NoticeFingerprintGenerator;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-orchestration;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
class ExternalNoticeCollectionServiceTests {

    private static final Instant FIRST_RUN_AT = Instant.parse("2026-09-01T01:00:00Z");
    private static final Instant SECOND_RUN_AT = Instant.parse("2026-09-01T02:00:00Z");

    @Autowired
    private PiaNoticeClassifier classifier;

    @Autowired
    private NoticeFingerprintGenerator fingerprintGenerator;

    @Autowired
    private ExternalNoticeChangeService changeService;

    @Autowired
    private ExternalNoticeRepository repository;

    private StubCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StubCollector();
    }

    @Test
    void firstCollectionStoresAllNoticesAsNewWithClassificationFingerprintAndAttachments() {
        collector.setNotices(List.of(piaNotice("300", "PIA 교육 본문"), generalNotice("301")));

        ExternalNoticeCollectionResult result = service(FIRST_RUN_AT).runCollection();

        assertEquals(2, result.getCollectedCount());
        assertEquals(2, result.getNewCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getUnchangedCount());
        assertEquals(1, result.getPiaRelatedCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(2, result.getNoticeResults().size());
        assertTrue(result.getNoticeResults().stream()
                .allMatch(change -> change.getChangeType() == NoticeChangeType.NEW));

        ExternalNotice pia = repository.findByExternalId(externalId("300")).orElseThrow();
        ExternalNotice general = repository.findByExternalId(externalId("301")).orElseThrow();
        assertTrue(pia.isPiaRelated());
        assertFalse(general.isPiaRelated());
        assertTrue(pia.getMatchedKeywords().contains("PIA"));
        assertNotNull(pia.getFingerprint());
        assertEquals(64, pia.getFingerprint().length());
        assertEquals(1, pia.getAttachments().size());
        assertEquals("교육 안내.hwp", pia.getAttachments().getFirst().getFileName());
        assertEquals(FIRST_RUN_AT, pia.getFirstSeenAt());
        assertEquals(FIRST_RUN_AT, pia.getLastSeenAt());
        assertEquals(FIRST_RUN_AT, general.getFirstSeenAt());
        assertEquals(FIRST_RUN_AT, general.getLastSeenAt());
    }

    @Test
    void repeatedSameCollectionIsUnchangedAndDoesNotCreateDuplicates() {
        List<CollectedNotice> notices = List.of(piaNotice("302", "동일 본문"), generalNotice("303"));
        collector.setNotices(notices);
        service(FIRST_RUN_AT).runCollection();

        ExternalNoticeCollectionResult result = service(SECOND_RUN_AT).runCollection();

        assertEquals(0, result.getNewCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(2, result.getUnchangedCount());
        assertEquals(2, repository.findAll().size());
        ExternalNotice restored = repository.findByExternalId(externalId("302")).orElseThrow();
        assertEquals(FIRST_RUN_AT, restored.getFirstSeenAt());
        assertEquals(SECOND_RUN_AT, restored.getLastSeenAt());
    }

    @Test
    void changedNoticeIsUpdatedAndUnchangedNoticeRemainsUnchanged() {
        collector.setNotices(List.of(piaNotice("304", "최초 본문"), generalNotice("305")));
        service(FIRST_RUN_AT).runCollection();
        CollectedNotice changed = CollectedNotice.builder()
                .source(NoticeSource.PRIVACY_PORTAL)
                .externalId(externalId("304"))
                .sourceNoticeId("304")
                .title("개인정보 영향평가 변경 안내")
                .publishedDate(LocalDate.of(2026, 9, 2))
                .detailUrl("https://example.com/notices/304")
                .body("변경된 PIA 교육 본문")
                .attachment(NoticeAttachment.builder()
                        .fileName("변경 첨부.pdf")
                        .fileUrl("https://example.com/files/changed")
                        .build())
                .build();
        collector.setNotices(List.of(changed, generalNotice("305")));

        ExternalNoticeCollectionResult result = service(SECOND_RUN_AT).runCollection();
        ExternalNotice restored = repository.findByExternalId(externalId("304")).orElseThrow();

        assertEquals(0, result.getNewCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(1, result.getUnchangedCount());
        assertEquals("개인정보 영향평가 변경 안내", restored.getTitle());
        assertEquals("변경된 PIA 교육 본문", restored.getBody());
        assertEquals("변경 첨부.pdf", restored.getAttachments().getFirst().getFileName());
        assertEquals(FIRST_RUN_AT, restored.getFirstSeenAt());
        assertEquals(SECOND_RUN_AT, restored.getLastSeenAt());
    }

    @Test
    void recordsIndividualFailureAndContinuesWithNextNotice() {
        CollectedNotice invalid = CollectedNotice.builder()
                .externalId(externalId("306"))
                .sourceNoticeId("306")
                .title("처리 실패 공지")
                .body("본문")
                .build();
        collector.setNotices(List.of(invalid, piaNotice("307", "정상 본문")));

        ExternalNoticeCollectionResult result = service(FIRST_RUN_AT).runCollection();

        assertEquals(2, result.getCollectedCount());
        assertEquals(1, result.getNewCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(externalId("306"), result.getFailures().getFirst().getExternalId());
        assertEquals("NullPointerException", result.getFailures().getFirst().getErrorType());
        assertTrue(repository.findByExternalId(externalId("306")).isEmpty());
        assertTrue(repository.findByExternalId(externalId("307")).isPresent());
    }

    @Test
    void propagatesCollectorFailureAsWholeCollectionFailure() {
        collector.setFailure(new ExternalNoticeCollectionException("PIA 게시판 수집 실패"));

        ExternalNoticeCollectionException exception = assertThrows(
                ExternalNoticeCollectionException.class,
                () -> service(FIRST_RUN_AT).runCollection()
        );

        assertEquals("PIA 게시판 수집 실패", exception.getMessage());
        assertTrue(repository.findAll().isEmpty());
    }

    private ExternalNoticeCollectionService service(Instant executionTime) {
        Clock clock = Clock.fixed(executionTime, ZoneOffset.UTC);
        return new ExternalNoticeCollectionService(
                collector,
                classifier,
                fingerprintGenerator,
                changeService,
                clock
        );
    }

    private CollectedNotice piaNotice(String sourceNoticeId, String body) {
        return CollectedNotice.builder()
                .source(NoticeSource.PRIVACY_PORTAL)
                .externalId(externalId(sourceNoticeId))
                .sourceNoticeId(sourceNoticeId)
                .title("개인정보 영향평가 PIA 전문교육 안내")
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl("https://example.com/notices/" + sourceNoticeId)
                .body(body)
                .attachment(NoticeAttachment.builder()
                        .fileName("교육 안내.hwp")
                        .fileUrl("https://example.com/files/guide")
                        .build())
                .build();
    }

    private CollectedNotice generalNotice(String sourceNoticeId) {
        return CollectedNotice.builder()
                .source(NoticeSource.PRIVACY_PORTAL)
                .externalId(externalId(sourceNoticeId))
                .sourceNoticeId(sourceNoticeId)
                .title("개인정보 포털 시스템 점검 안내")
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl("https://example.com/notices/" + sourceNoticeId)
                .body("서비스 점검 시간 안내")
                .build();
    }

    private String externalId(String sourceNoticeId) {
        return "PRIVACY_PORTAL:BOARD:" + sourceNoticeId;
    }

    private static class StubCollector implements ExternalNoticeCollector {

        private List<CollectedNotice> notices = List.of();
        private RuntimeException failure;

        @Override
        public NoticeSource getSource() {
            return NoticeSource.PRIVACY_PORTAL;
        }

        @Override
        public List<CollectedNotice> collect() {
            if (failure != null) {
                throw failure;
            }
            return notices;
        }

        void setNotices(List<CollectedNotice> notices) {
            this.notices = notices;
            this.failure = null;
        }

        void setFailure(RuntimeException failure) {
            this.failure = failure;
        }
    }
}
