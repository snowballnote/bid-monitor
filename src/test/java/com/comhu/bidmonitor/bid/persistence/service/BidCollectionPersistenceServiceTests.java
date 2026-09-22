package com.comhu.bidmonitor.bid.persistence.service;

import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-collection-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class BidCollectionPersistenceServiceTests {

    private static final Instant COLLECTED_AT = Instant.parse("2026-09-22T03:00:00Z");

    @Autowired
    private BidCollectionPersistenceService service;

    @Autowired
    private BidNoticeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearBidNotices() {
        jdbcTemplate.update("DELETE FROM bid_notice_version");
        jdbcTemplate.update("DELETE FROM bid_notice");
    }

    @Test
    void storesG2bExpresswayAndD2bResultsWithSourceMappings() {
        BidQualificationDto g2b = candidate("G2B", "G2B-1", null, "G2B 공고");
        g2b.setBidNtceDt("2026-09-22 09:00:00");
        BidQualificationDto expressway = candidate(
                "KOREA_EXPRESSWAY", "EXPRESSWAY-1", "2", "도로공사 공고"
        );
        expressway.setBidNtceDt("2026-09-22 09:30");
        BidQualificationDto d2b = candidate("D2B", "2026:11:D2B-1", "3", "D2B 공고");
        d2b.setBidNtceDt("20260922");
        d2b.setDetailUrl(null);
        d2b.setBidNtceDtlUrl(null);

        BidCollectionPersistenceResult result = service.persist(List.of(
                BidSourceCollectionResult.success("G2B", List.of(g2b)),
                BidSourceCollectionResult.success("KOREA_EXPRESSWAY", List.of(expressway)),
                BidSourceCollectionResult.success("D2B", List.of(d2b))
        ), COLLECTED_AT);

        assertEquals(3, result.successfulSourceCount());
        assertEquals(3, repository.findAll().size());
        BidNotice savedG2b = repository.findByIdentity("G2B", "G2B-1", null).orElseThrow();
        assertEquals("G2B 공고", savedG2b.getTitle());
        assertEquals("테스트 발주기관", savedG2b.getOrderingOrganization());
        assertEquals(LocalDateTime.of(2026, 9, 22, 9, 0), savedG2b.getPublishedAt());
        assertEquals("제한경쟁", savedG2b.getContractMethod());
        assertEquals("전자입찰", savedG2b.getBidMethod());
        assertEquals("NORMAL", savedG2b.getNoticeStatusCode());
        assertTrue(savedG2b.getAnalysisResult().contains("reviewStatus"));
        assertNull(repository.findByIdentity("D2B", "2026:11:D2B-1", "3")
                .orElseThrow().getDetailUrl());
    }

    @Test
    void separatesSameNoticeIdBySourceAndRevision() {
        BidQualificationDto g2b = candidate("G2B", "SHARED", null, "G2B");
        BidQualificationDto d2bFirst = candidate("D2B", "SHARED", "1", "D2B 1차");
        BidQualificationDto d2bSecond = candidate("D2B", "SHARED", "2", "D2B 2차");

        service.persist(List.of(
                BidSourceCollectionResult.success("G2B", List.of(g2b)),
                BidSourceCollectionResult.success("D2B", List.of(d2bFirst, d2bSecond))
        ), COLLECTED_AT);

        assertEquals(3, repository.findAll().size());
        assertTrue(repository.findByIdentity("G2B", "SHARED", null).isPresent());
        assertTrue(repository.findByIdentity("D2B", "SHARED", "1").isPresent());
        assertTrue(repository.findByIdentity("D2B", "SHARED", "2").isPresent());
    }

    @Test
    void sameContentKeepsHashAcrossCollectionTimesAndUiAnalysisChanges() {
        BidQualificationDto first = candidate("G2B", "HASH-1", null, "동일 공고");
        first.setReviewStatus("검토대상");
        BidSourcePersistenceResult firstResult = service.persist(List.of(
                BidSourceCollectionResult.success("G2B", List.of(first))
        ), COLLECTED_AT).sourceResults().getFirst();
        BidNotice storedFirst = repository.findByIdentity("G2B", "HASH-1", null).orElseThrow();

        BidQualificationDto repeated = candidate("G2B", "HASH-1", null, "동일 공고");
        repeated.setReviewStatus("추가확인필요");
        repeated.setReviewReason("UI 판정 사유 변경");
        BidSourcePersistenceResult repeatedResult = service.persist(List.of(
                BidSourceCollectionResult.success("G2B", List.of(repeated))
        ), COLLECTED_AT.plusSeconds(3600)).sourceResults().getFirst();
        BidNotice storedAgain = repository.findByIdentity("G2B", "HASH-1", null).orElseThrow();

        assertEquals(1, firstResult.newCount());
        assertEquals(1, repeatedResult.unchangedCount());
        assertEquals(storedFirst.getContentHash(), storedAgain.getContentHash());
        assertEquals(COLLECTED_AT, storedAgain.getFirstSeenAt());
        assertEquals(COLLECTED_AT.plusSeconds(3600), storedAgain.getLastSeenAt());
        assertTrue(repository.findVersions(storedAgain.getId()).isEmpty());
    }

    @Test
    void changedSourceContentCreatesNewHashAndPreservesSnapshot() {
        BidQualificationDto original = candidate("D2B", "2026:7:CHANGE-1", "4", "변경 전 공고");
        service.persist(List.of(BidSourceCollectionResult.success("D2B", List.of(original))), COLLECTED_AT);
        BidNotice before = repository.findByIdentity("D2B", "2026:7:CHANGE-1", "4").orElseThrow();

        BidQualificationDto changed = candidate("D2B", "2026:7:CHANGE-1", "4", "변경 후 공고");
        changed.setNoticeStatus("정정");
        changed.setNoticeStatusCode("CORRECTED");
        BidSourcePersistenceResult result = service.persist(List.of(
                BidSourceCollectionResult.success("D2B", List.of(changed))
        ), COLLECTED_AT.plusSeconds(60)).sourceResults().getFirst();

        BidNotice after = repository.findByIdentity("D2B", "2026:7:CHANGE-1", "4").orElseThrow();
        assertEquals(1, result.changedCount());
        assertNotEquals(before.getContentHash(), after.getContentHash());
        assertEquals("CORRECTED", after.getNoticeStatusCode());
        assertEquals(1, repository.findVersions(after.getId()).size());
        assertEquals(before.getContentHash(), repository.findVersions(after.getId()).getFirst().getContentHash());
    }

    @Test
    void failedSourceDoesNotDiscardSuccessfulSourceResults() {
        BidQualificationDto g2b = candidate("G2B", "PARTIAL-1", null, "성공 공고");

        BidCollectionPersistenceResult result = service.persist(List.of(
                BidSourceCollectionResult.failure("D2B", "SOURCE_UNAVAILABLE"),
                BidSourceCollectionResult.success("G2B", List.of(g2b)),
                BidSourceCollectionResult.failure("KOREA_EXPRESSWAY", "COLLECTION_TIMEOUT")
        ), COLLECTED_AT);

        assertEquals(1, result.successfulSourceCount());
        assertEquals(2, result.failedSourceCount());
        assertEquals(1, repository.findAll().size());
        assertTrue(repository.findByIdentity("G2B", "PARTIAL-1", null).isPresent());
    }

    @Test
    void persistenceFailureRollsBackOnlyThatSource() {
        BidQualificationDto valid = candidate("G2B", "VALID-1", null, "정상 공고");
        BidQualificationDto firstD2b = candidate("D2B", "ROLLBACK-1", "1", "롤백 대상");
        BidQualificationDto invalidD2b = candidate("D2B", "ROLLBACK-2", "1", "잘못된 날짜");
        invalidD2b.setBidClseDt("not-a-date");

        BidCollectionPersistenceResult result = service.persist(List.of(
                BidSourceCollectionResult.success("G2B", List.of(valid)),
                BidSourceCollectionResult.success("D2B", List.of(firstD2b, invalidD2b))
        ), COLLECTED_AT);

        assertEquals(1, result.successfulSourceCount());
        assertEquals(1, result.failedSourceCount());
        assertEquals(1, repository.findAll().size());
        assertTrue(repository.findByIdentity("G2B", "VALID-1", null).isPresent());
        assertTrue(repository.findByIdentity("D2B", "ROLLBACK-1", "1").isEmpty());
    }

    @Test
    void allSourceFailuresRaiseExplicitFailureInsteadOfReturningEmptySuccess() {
        BidCollectionPersistenceException exception = assertThrows(
                BidCollectionPersistenceException.class,
                () -> service.persist(List.of(
                        BidSourceCollectionResult.failure("G2B", "SOURCE_UNAVAILABLE"),
                        BidSourceCollectionResult.failure("KOREA_EXPRESSWAY", "COLLECTION_TIMEOUT"),
                        BidSourceCollectionResult.failure("D2B", "DAILY_QUOTA_EXHAUSTED")
                ), COLLECTED_AT)
        );

        assertEquals(0, exception.getResult().successfulSourceCount());
        assertEquals(3, exception.getResult().failedSourceCount());
        assertTrue(repository.findAll().isEmpty());
    }

    private BidQualificationDto candidate(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            String title
    ) {
        BidQualificationDto candidate = new BidQualificationDto();
        candidate.setSourceCode(sourceCode);
        candidate.setSourceNoticeId(sourceNoticeId);
        candidate.setRevision(revision);
        candidate.setBidNtceNo("2026-001");
        candidate.setBidNtceNm(title);
        candidate.setNtceInsttNm("테스트 발주기관");
        candidate.setBidNtceDt("2026-09-22 09:00");
        candidate.setBidClseDt("2026-09-30 18:00");
        candidate.setBidOpeningDt("2026-10-01 10:00");
        candidate.setContractMethod("제한경쟁");
        candidate.setBidForm("전자입찰");
        candidate.setNoticeStatus("정상");
        candidate.setNoticeStatusCode("NORMAL");
        candidate.setAsignBdgtAmt("100000000");
        candidate.setDetailUrl("https://example.test/bids/2026-001");
        candidate.setBidNtceDtlUrl(candidate.getDetailUrl());
        candidate.setLicenseLimit("정보시스템 감리법인");
        candidate.setLicenseGroups(List.of());
        candidate.setParticipationRegion("전국");
        candidate.setSucsfbidMthdNm("적격심사");
        candidate.setSucsfbidMthdCd("QUALIFICATION");
        candidate.setSucsfbidMthdAppStd("일반용역 적격심사");
        candidate.setAttachments(List.of(new BidAttachmentDto(
                "공고문.pdf", "https://example.test/files/notice.pdf", "공고문", "NOT_ANALYZED"
        )));
        candidate.setReviewStatus("검토대상");
        candidate.setReviewReason("테스트 판정");
        return candidate;
    }
}
