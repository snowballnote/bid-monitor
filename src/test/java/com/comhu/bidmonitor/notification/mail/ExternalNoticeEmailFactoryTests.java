package com.comhu.bidmonitor.notification.mail;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalNoticeEmailFactoryTests {

    private final ExternalNoticeEmailFactory factory = new ExternalNoticeEmailFactory();

    @Test
    void newEmailContainsSummaryAndValidDetailUrl() {
        EmailMessage message = factory.create(
                delivery(NoticeChangeType.NEW),
                notice("개인정보 영향평가 전문교육 안내", "https://example.com/notices/1")
        );

        assertTrue(message.subject().startsWith("[Biz Assist] PIA 중요공지 신규 - "));
        assertTrue(message.body().contains("신규"));
        assertTrue(message.body().contains("2026-09-01"));
        assertTrue(message.body().contains("개인정보 영향평가, 전문교육"));
        assertTrue(message.body().contains("PRIVACY_PORTAL"));
        assertTrue(message.body().contains("https://example.com/notices/1"));
    }

    @Test
    void missingOrInvalidDetailUrlIsOmittedWithoutPreventingMessageCreation() {
        EmailMessage missingUrl = factory.create(delivery(NoticeChangeType.UPDATED), notice("변경 안내", null));
        EmailMessage invalidUrl = factory.create(
                delivery(NoticeChangeType.UPDATED),
                notice("변경 안내", "javascript:alert(1)")
        );

        assertTrue(missingUrl.subject().contains("중요공지 변경"));
        assertFalse(missingUrl.body().contains("원문 보기:"));
        assertFalse(invalidUrl.body().contains("javascript:"));
    }

    @Test
    void htmlAndHeaderControlCharactersAreReducedToSafePlainText() {
        EmailMessage message = factory.create(
                delivery(NoticeChangeType.NEW),
                notice("<script>alert('x')</script><b>교육 안내</b>\r\nBcc: attacker@example.com", null)
        );

        assertFalse(message.subject().contains("<script>"));
        assertFalse(message.subject().contains("\r"));
        assertFalse(message.subject().contains("\n"));
        assertTrue(message.subject().contains("교육 안내"));
    }

    private NotificationDelivery delivery(NoticeChangeType changeType) {
        return NotificationDelivery.builder()
                .id(1L)
                .channel(NotificationChannel.EMAIL)
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .sourceCode("PRIVACY_PORTAL")
                .externalId("notice-1")
                .changeType(changeType)
                .contentFingerprint("a".repeat(64))
                .status(NotificationDeliveryStatus.PENDING)
                .build();
    }

    private ExternalNotice notice(String title, String detailUrl) {
        return ExternalNotice.builder()
                .id(1L)
                .sourceCode("PRIVACY_PORTAL")
                .externalId("notice-1")
                .sourceNoticeId("notice-1")
                .title(title)
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl(detailUrl)
                .piaRelated(true)
                .matchedKeywords(List.of("개인정보 영향평가", "전문교육"))
                .build();
    }
}
