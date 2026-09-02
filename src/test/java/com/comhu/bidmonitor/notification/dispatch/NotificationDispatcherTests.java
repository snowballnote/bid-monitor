package com.comhu.bidmonitor.notification.dispatch;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.mail.BizAssistMailProperties;
import com.comhu.bidmonitor.notification.mail.EmailSender;
import com.comhu.bidmonitor.notification.mail.ExternalNoticeEmailFactory;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDispatcherTests {

    private static final Instant SENT_AT = Instant.parse("2026-09-02T03:00:00Z");
    private static final String SOURCE_CODE = "PRIVACY_PORTAL";

    private NotificationDeliveryRepository deliveryRepository;
    private ExternalNoticeRepository noticeRepository;
    private EmailSender emailSender;
    private ExternalNoticeEmailFactory emailFactory;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(NotificationDeliveryRepository.class);
        noticeRepository = mock(ExternalNoticeRepository.class);
        emailSender = mock(EmailSender.class);
        emailFactory = new ExternalNoticeEmailFactory();
    }

    @Test
    void pendingNewSendsOneEmailAndMarksSent() {
        NotificationDelivery delivery = delivery(1L, "notice-new", NoticeChangeType.NEW);
        arrange(delivery, notice("notice-new", "신규 공지", "https://example.com/new"));

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(1, result.sentCount());
        assertEquals(0, result.failedCount());
        verify(emailSender).send(any());
        verify(deliveryRepository).markSent(1L, SENT_AT);
        verify(deliveryRepository, never()).markFailed(anyLong(), any());
    }

    @Test
    void pendingUpdatedSendsOneEmailAndMarksSent() {
        NotificationDelivery delivery = delivery(2L, "notice-updated", NoticeChangeType.UPDATED);
        arrange(delivery, notice("notice-updated", "변경 공지", "https://example.com/updated"));

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(1, result.sentCount());
        verify(emailSender).send(any());
        verify(deliveryRepository).markSent(2L, SENT_AT);
    }

    @Test
    void sendFailureMarksFailedWithCredentialFreeReason() {
        NotificationDelivery delivery = delivery(3L, "notice-failed", NoticeChangeType.NEW);
        arrange(delivery, notice("notice-failed", "실패 공지", null));
        doThrow(new IllegalStateException("secret-password should never be persisted"))
                .when(emailSender).send(any());

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        assertEquals(1, result.failedCount());
        verify(deliveryRepository).markFailed(org.mockito.ArgumentMatchers.eq(3L), reasonCaptor.capture());
        assertFalse(reasonCaptor.getValue().contains("secret-password"));
        verify(deliveryRepository, never()).markSent(anyLong(), any());
    }

    @Test
    void oneFailureDoesNotStopTheNextPendingDelivery() {
        NotificationDelivery first = delivery(4L, "notice-first", NoticeChangeType.NEW);
        NotificationDelivery second = delivery(5L, "notice-second", NoticeChangeType.UPDATED);
        when(deliveryRepository.findPending(NotificationChannel.EMAIL, NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(first, second));
        when(noticeRepository.findByExternalId("notice-first"))
                .thenReturn(Optional.of(notice("notice-first", "첫 번째", null)));
        when(noticeRepository.findByExternalId("notice-second"))
                .thenReturn(Optional.of(notice("notice-second", "두 번째", null)));
        doThrow(new IllegalStateException("first failed"))
                .doNothing()
                .when(emailSender).send(any());

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(1, result.sentCount());
        assertEquals(1, result.failedCount());
        verify(emailSender, times(2)).send(any());
        verify(deliveryRepository).markFailed(org.mockito.ArgumentMatchers.eq(4L), any());
        verify(deliveryRepository).markSent(5L, SENT_AT);
    }

    @Test
    void sentDeliveryIsNotSentAgainWhenRepositoryNoLongerReturnsItAsPending() {
        NotificationDelivery delivery = delivery(6L, "notice-once", NoticeChangeType.NEW);
        when(deliveryRepository.findPending(NotificationChannel.EMAIL, NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(delivery))
                .thenReturn(List.of());
        when(noticeRepository.findByExternalId("notice-once"))
                .thenReturn(Optional.of(notice("notice-once", "한 번만", null)));
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        verify(emailSender).send(any());
        verify(deliveryRepository).markSent(6L, SENT_AT);
    }

    @Test
    void disabledDispatcherDoesNotQuerySendOrChangeStatus() {
        NotificationDispatchResult result = dispatcher(false).dispatchPending();

        assertEquals(NotificationDispatchResult.disabledResult(), result);
        verifyNoInteractions(deliveryRepository, noticeRepository, emailSender);
    }

    private void arrange(NotificationDelivery delivery, ExternalNotice notice) {
        when(deliveryRepository.findPending(NotificationChannel.EMAIL, NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(delivery));
        when(noticeRepository.findByExternalId(delivery.getExternalId())).thenReturn(Optional.of(notice));
    }

    private NotificationDispatcher dispatcher(boolean enabled) {
        return new NotificationDispatcher(
                deliveryRepository,
                noticeRepository,
                emailSender,
                emailFactory,
                properties(enabled),
                Clock.fixed(SENT_AT, ZoneOffset.UTC)
        );
    }

    private BizAssistMailProperties properties(boolean enabled) {
        return new BizAssistMailProperties(
                enabled,
                "smtp.example.com",
                587,
                "username",
                "password",
                "sender@example.com",
                "recipient@example.com",
                true,
                true,
                false,
                5_000,
                10_000,
                10_000
        );
    }

    private NotificationDelivery delivery(Long id, String externalId, NoticeChangeType changeType) {
        return NotificationDelivery.builder()
                .id(id)
                .channel(NotificationChannel.EMAIL)
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .changeType(changeType)
                .contentFingerprint(String.valueOf(id).repeat(64).substring(0, 64))
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(SENT_AT.minusSeconds(60))
                .build();
    }

    private ExternalNotice notice(String externalId, String title, String detailUrl) {
        return ExternalNotice.builder()
                .id(100L)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .sourceNoticeId(externalId)
                .title(title)
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl(detailUrl)
                .piaRelated(true)
                .matchedKeywords(List.of("개인정보 영향평가", "전문교육"))
                .fingerprint("f".repeat(64))
                .build();
    }
}
