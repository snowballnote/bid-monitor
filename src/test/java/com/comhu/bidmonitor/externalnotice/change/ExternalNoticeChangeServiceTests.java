package com.comhu.bidmonitor.externalnotice.change;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-change;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
class ExternalNoticeChangeServiceTests {

    private static final Instant FIRST_SEEN_AT = Instant.parse("2026-08-31T01:00:00Z");
    private static final Instant SECOND_SEEN_AT = Instant.parse("2026-08-31T02:00:00Z");

    @Autowired
    private ExternalNoticeChangeService changeService;

    @Autowired
    private ExternalNoticeRepository repository;

    @Test
    void savesFirstNoticeAsNew() {
        ExternalNotice current = notice("200", "최초 제목", "최초 본문", "a".repeat(64), FIRST_SEEN_AT);

        NoticeChangeResult result = changeService.process(current);

        assertEquals(NoticeChangeType.NEW, result.getChangeType());
        assertEquals(current.getExternalId(), result.getExternalId());
        assertNull(result.getPreviousFingerprint());
        assertEquals(current.getFingerprint(), result.getCurrentFingerprint());
        assertEquals(result.getNoticeId(), repository.findByExternalId(current.getExternalId()).orElseThrow().getId());
    }

    @Test
    void marksSameFingerprintUnchangedAndUpdatesOnlyLastSeenAt() {
        ExternalNotice original = notice("201", "최초 제목", "최초 본문", "b".repeat(64), FIRST_SEEN_AT);
        NoticeChangeResult firstResult = changeService.process(original);
        ExternalNotice observedAgain = notice(
                "201",
                "저장되면 안 되는 제목",
                "저장되면 안 되는 본문",
                original.getFingerprint(),
                SECOND_SEEN_AT
        );

        NoticeChangeResult result = changeService.process(observedAgain);
        ExternalNotice restored = repository.findByExternalId(original.getExternalId()).orElseThrow();

        assertEquals(NoticeChangeType.UNCHANGED, result.getChangeType());
        assertEquals(firstResult.getNoticeId(), result.getNoticeId());
        assertEquals(original.getFingerprint(), result.getPreviousFingerprint());
        assertEquals(original.getFingerprint(), result.getCurrentFingerprint());
        assertEquals("최초 제목", restored.getTitle());
        assertEquals("최초 본문", restored.getBody());
        assertEquals(FIRST_SEEN_AT, restored.getFirstSeenAt());
        assertEquals(SECOND_SEEN_AT, restored.getLastSeenAt());
    }

    @Test
    void updatesChangedContentAndReplacesAttachmentsWhileKeepingIdentity() {
        ExternalNotice original = notice("202", "최초 제목", "최초 본문", "c".repeat(64), FIRST_SEEN_AT);
        NoticeChangeResult firstResult = changeService.process(original);
        ExternalNotice changed = ExternalNotice.builder()
                .sourceCode("CHANGED_SOURCE_MUST_NOT_BE_SAVED")
                .externalId(original.getExternalId())
                .sourceNoticeId("changed-source-id")
                .title("변경 제목")
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl("https://example.com/notices/202/changed")
                .body("변경 본문")
                .piaRelated(false)
                .matchedKeyword("갱신")
                .classificationReason("변경된 분류 결과")
                .fingerprint("d".repeat(64))
                .firstSeenAt(Instant.parse("2030-01-01T00:00:00Z"))
                .lastSeenAt(SECOND_SEEN_AT)
                .attachment(attachment("최신 첨부.pdf", "https://example.com/files/latest"))
                .build();

        NoticeChangeResult result = changeService.process(changed);
        ExternalNotice restored = repository.findByExternalId(original.getExternalId()).orElseThrow();

        assertEquals(NoticeChangeType.UPDATED, result.getChangeType());
        assertEquals(firstResult.getNoticeId(), result.getNoticeId());
        assertEquals(original.getFingerprint(), result.getPreviousFingerprint());
        assertEquals(changed.getFingerprint(), result.getCurrentFingerprint());
        assertEquals("PRIVACY_PORTAL", restored.getSourceCode());
        assertEquals("202", restored.getSourceNoticeId());
        assertEquals("변경 제목", restored.getTitle());
        assertEquals(LocalDate.of(2026, 9, 1), restored.getPublishedDate());
        assertEquals("https://example.com/notices/202/changed", restored.getDetailUrl());
        assertEquals("변경 본문", restored.getBody());
        assertFalse(restored.isPiaRelated());
        assertEquals(List.of("갱신"), restored.getMatchedKeywords());
        assertEquals("변경된 분류 결과", restored.getClassificationReason());
        assertEquals(changed.getFingerprint(), restored.getFingerprint());
        assertEquals(FIRST_SEEN_AT, restored.getFirstSeenAt());
        assertEquals(SECOND_SEEN_AT, restored.getLastSeenAt());
        assertEquals(1, restored.getAttachments().size());
        assertEquals("최신 첨부.pdf", restored.getAttachments().getFirst().getFileName());
        assertEquals("https://example.com/files/latest", restored.getAttachments().getFirst().getFileUrl());
    }

    @Test
    void processingSameNoticeRepeatedlyDoesNotCreateDuplicateRows() {
        ExternalNotice current = notice("203", "제목", "본문", "e".repeat(64), FIRST_SEEN_AT);

        assertEquals(NoticeChangeType.NEW, changeService.process(current).getChangeType());
        assertEquals(NoticeChangeType.UNCHANGED, changeService.process(
                notice("203", "제목", "본문", current.getFingerprint(), SECOND_SEEN_AT)
        ).getChangeType());

        assertEquals(1, repository.findAll().size());
    }

    private ExternalNotice notice(
            String sourceNoticeId,
            String title,
            String body,
            String fingerprint,
            Instant lastSeenAt
    ) {
        return ExternalNotice.builder()
                .sourceCode("PRIVACY_PORTAL")
                .externalId("PRIVACY_PORTAL:BOARD:" + sourceNoticeId)
                .sourceNoticeId(sourceNoticeId)
                .title(title)
                .publishedDate(LocalDate.of(2026, 8, 31))
                .detailUrl("https://example.com/notices/" + sourceNoticeId)
                .body(body)
                .piaRelated(true)
                .matchedKeywords(List.of("PIA", "전문교육"))
                .classificationReason("PIA 핵심 키워드 발견")
                .fingerprint(fingerprint)
                .firstSeenAt(FIRST_SEEN_AT)
                .lastSeenAt(lastSeenAt)
                .attachment(attachment("기존 첨부.hwp", "https://example.com/files/original"))
                .attachment(attachment("삭제될 첨부.pdf", "https://example.com/files/removed"))
                .build();
    }

    private ExternalNoticeAttachment attachment(String fileName, String fileUrl) {
        return ExternalNoticeAttachment.builder()
                .fileName(fileName)
                .fileUrl(fileUrl)
                .build();
    }
}
