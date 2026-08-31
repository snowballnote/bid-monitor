package com.comhu.bidmonitor.externalnotice.persistence.jdbc;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-repository;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
class JdbcExternalNoticeRepositoryTests {

    @Autowired
    private ExternalNoticeRepository repository;

    @Test
    void savesAndRestoresAllNoticeFields() {
        ExternalNotice original = createNotice("100", true, List.of("PIA", "전문교육"));

        ExternalNotice saved = repository.save(original);

        assertNotNull(saved.getId());
        assertEquals(original.getSourceCode(), saved.getSourceCode());
        assertEquals(original.getExternalId(), saved.getExternalId());
        assertEquals(original.getSourceNoticeId(), saved.getSourceNoticeId());
        assertEquals(original.getTitle(), saved.getTitle());
        assertEquals(original.getPublishedDate(), saved.getPublishedDate());
        assertEquals(original.getDetailUrl(), saved.getDetailUrl());
        assertEquals(original.getBody(), saved.getBody());
        assertEquals(original.isPiaRelated(), saved.isPiaRelated());
        assertEquals(original.getMatchedKeywords(), saved.getMatchedKeywords());
        assertEquals(original.getClassificationReason(), saved.getClassificationReason());
        assertEquals(original.getFingerprint(), saved.getFingerprint());
        assertEquals(original.getFirstSeenAt(), saved.getFirstSeenAt());
        assertEquals(original.getLastSeenAt(), saved.getLastSeenAt());
    }

    @Test
    void findsNoticeAndChecksExistenceByExternalId() {
        ExternalNotice notice = createNotice("101", false, List.of());
        repository.save(notice);

        assertTrue(repository.existsByExternalId(notice.getExternalId()));
        assertFalse(repository.existsByExternalId("PRIVACY_PORTAL:BOARD:missing"));
        assertTrue(repository.findByExternalId(notice.getExternalId()).isPresent());
        assertTrue(repository.findByExternalId("PRIVACY_PORTAL:BOARD:missing").isEmpty());
    }

    @Test
    void savesAndRestoresMultipleAttachmentsInOriginalOrder() {
        ExternalNotice saved = repository.save(createNotice("102", true, List.of("영향평가")));

        assertEquals(2, saved.getAttachments().size());
        ExternalNoticeAttachment first = saved.getAttachments().getFirst();
        ExternalNoticeAttachment second = saved.getAttachments().get(1);
        assertNotNull(first.getId());
        assertEquals(saved.getId(), first.getExternalNoticeId());
        assertEquals("교육 안내.hwp", first.getFileName());
        assertEquals("https://example.com/files/guide", first.getFileUrl());
        assertEquals("신청서.pdf", second.getFileName());
        assertEquals("https://example.com/files/form", second.getFileUrl());
    }

    @Test
    void findsAllSavedNotices() {
        repository.save(createNotice("103", true, List.of("PIA")));
        repository.save(createNotice("104", false, List.of()));

        List<ExternalNotice> notices = repository.findAll();

        assertEquals(2, notices.size());
        assertEquals("PRIVACY_PORTAL:BOARD:103", notices.getFirst().getExternalId());
        assertEquals("PRIVACY_PORTAL:BOARD:104", notices.get(1).getExternalId());
    }

    @Test
    void rejectsDuplicateExternalId() {
        ExternalNotice original = createNotice("105", true, List.of("PIA"));
        repository.save(original);

        ExternalNotice duplicate = createNotice("105", false, List.of());

        assertThrows(DuplicateKeyException.class, () -> repository.save(duplicate));
        assertEquals(1, repository.findAll().size());
    }

    private ExternalNotice createNotice(String sourceNoticeId, boolean piaRelated, List<String> keywords) {
        return ExternalNotice.builder()
                .sourceCode("PRIVACY_PORTAL")
                .externalId("PRIVACY_PORTAL:BOARD:" + sourceNoticeId)
                .sourceNoticeId(sourceNoticeId)
                .title("개인정보 영향평가 교육 안내 " + sourceNoticeId)
                .publishedDate(LocalDate.of(2026, 8, 31))
                .detailUrl("https://example.com/notices/" + sourceNoticeId)
                .body("교육 신청과 접수 일정을 안내합니다.")
                .piaRelated(piaRelated)
                .matchedKeywords(keywords)
                .classificationReason(piaRelated ? "PIA 핵심 키워드 발견" : "관련 키워드 없음")
                .fingerprint("a".repeat(64))
                .firstSeenAt(Instant.parse("2026-08-31T01:00:00Z"))
                .lastSeenAt(Instant.parse("2026-08-31T02:00:00Z"))
                .attachment(ExternalNoticeAttachment.builder()
                        .fileName("교육 안내.hwp")
                        .fileUrl("https://example.com/files/guide")
                        .build())
                .attachment(ExternalNoticeAttachment.builder()
                        .fileName("신청서.pdf")
                        .fileUrl("https://example.com/files/form")
                        .build())
                .build();
    }
}
