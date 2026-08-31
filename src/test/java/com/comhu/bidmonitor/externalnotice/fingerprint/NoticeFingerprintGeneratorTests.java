package com.comhu.bidmonitor.externalnotice.fingerprint;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeAttachment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoticeFingerprintGeneratorTests {

    private static final LocalDate PUBLISHED_DATE = LocalDate.of(2026, 8, 31);

    private final NoticeFingerprintGenerator generator = new NoticeFingerprintGenerator();

    @Test
    void generatesSameFingerprintForSameNoticeContent() {
        CollectedNotice first = notice("교육 안내", PUBLISHED_DATE, "교육 본문", List.of(), "first");
        CollectedNotice second = notice("교육 안내", PUBLISHED_DATE, "교육 본문", List.of(), "second");

        String firstFingerprint = generator.generate(first);

        assertEquals(firstFingerprint, generator.generate(second));
        assertTrue(firstFingerprint.matches("[0-9a-f]{64}"));
    }

    @Test
    void ignoresBodyWhitespaceAndLineBreakDifferences() {
        CollectedNotice first = notice("교육 안내", PUBLISHED_DATE, "신청 기간은  9월입니다.", List.of(), "1");
        CollectedNotice second = notice("교육 안내", PUBLISHED_DATE, "  신청 기간은\n\t9월입니다.  ", List.of(), "2");

        assertEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenTitleChanges() {
        CollectedNotice first = notice("상반기 교육 안내", PUBLISHED_DATE, "본문", List.of(), "1");
        CollectedNotice second = notice("하반기 교육 안내", PUBLISHED_DATE, "본문", List.of(), "2");

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenBodyChanges() {
        CollectedNotice first = notice("교육 안내", PUBLISHED_DATE, "접수 시작", List.of(), "1");
        CollectedNotice second = notice("교육 안내", PUBLISHED_DATE, "접수 마감", List.of(), "2");

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenPublishedDateChanges() {
        CollectedNotice first = notice("교육 안내", PUBLISHED_DATE, "본문", List.of(), "1");
        CollectedNotice second = notice("교육 안내", PUBLISHED_DATE.plusDays(1), "본문", List.of(), "2");

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenAttachmentIsAdded() {
        CollectedNotice first = notice("교육 안내", PUBLISHED_DATE, "본문", List.of(), "1");
        CollectedNotice second = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(attachment("안내문.hwp", "https://example.com/files/1")),
                "2"
        );

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenAttachmentFileNameChanges() {
        CollectedNotice first = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(attachment("안내문.hwp", "https://example.com/files/1")),
                "1"
        );
        CollectedNotice second = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(attachment("수정 안내문.hwp", "https://example.com/files/1")),
                "2"
        );

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void changesFingerprintWhenAttachmentFileUrlChanges() {
        CollectedNotice first = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(attachment("안내문.hwp", "https://example.com/files/1")),
                "1"
        );
        CollectedNotice second = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(attachment("안내문.hwp", "https://example.com/files/2")),
                "2"
        );

        assertNotEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void ignoresAttachmentOrderDifferences() {
        NoticeAttachment firstAttachment = attachment("신청서.hwp", "https://example.com/files/1");
        NoticeAttachment secondAttachment = attachment("안내문.pdf", "https://example.com/files/2");
        CollectedNotice first = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(firstAttachment, secondAttachment),
                "1"
        );
        CollectedNotice second = notice(
                "교육 안내",
                PUBLISHED_DATE,
                "본문",
                List.of(secondAttachment, firstAttachment),
                "2"
        );

        assertEquals(generator.generate(first), generator.generate(second));
    }

    @Test
    void handlesNullAndEmptyBodyAsSameContent() {
        CollectedNotice nullBody = notice("교육 안내", PUBLISHED_DATE, null, List.of(), "1");
        CollectedNotice emptyBody = notice("교육 안내", PUBLISHED_DATE, "", List.of(), "2");

        assertDoesNotThrow(() -> generator.generate(nullBody));
        assertEquals(generator.generate(nullBody), generator.generate(emptyBody));
    }

    private CollectedNotice notice(
            String title,
            LocalDate publishedDate,
            String body,
            List<NoticeAttachment> attachments,
            String identitySuffix
    ) {
        return CollectedNotice.builder()
                .externalId("EXTERNAL:" + identitySuffix)
                .sourceNoticeId("SOURCE:" + identitySuffix)
                .title(title)
                .publishedDate(publishedDate)
                .detailUrl("https://example.com/notices/" + identitySuffix)
                .body(body)
                .attachments(attachments)
                .build();
    }

    private NoticeAttachment attachment(String fileName, String fileUrl) {
        return NoticeAttachment.builder()
                .fileName(fileName)
                .fileUrl(fileUrl)
                .build();
    }
}
