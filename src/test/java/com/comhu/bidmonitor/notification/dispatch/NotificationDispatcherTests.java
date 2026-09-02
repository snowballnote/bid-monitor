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
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDelivery;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    private final Map<String, NotificationSubscriberDelivery> subscriberDeliveries = new LinkedHashMap<>();
    private final AtomicLong subscriberDeliveryIds = new AtomicLong();

    private NotificationDeliveryRepository deliveryRepository;
    private NotificationSubscriberDeliveryRepository subscriberDeliveryRepository;
    private NotificationSubscriberService subscriberService;
    private ExternalNoticeRepository noticeRepository;
    private EmailSender emailSender;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(NotificationDeliveryRepository.class);
        subscriberDeliveryRepository = mock(NotificationSubscriberDeliveryRepository.class);
        subscriberService = mock(NotificationSubscriberService.class);
        noticeRepository = mock(ExternalNoticeRepository.class);
        emailSender = mock(EmailSender.class);

        when(subscriberDeliveryRepository.createPendingIfAbsent(anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> createSubscriberDelivery(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));
        when(subscriberDeliveryRepository.findByNotificationDeliveryId(anyLong()))
                .thenAnswer(invocation -> subscriberDeliveries.values().stream()
                        .filter(delivery -> delivery.getNotificationDeliveryId().equals(invocation.getArgument(0)))
                        .toList());
        doAnswer(invocation -> {
            updateSubscriberDelivery(
                    invocation.getArgument(0),
                    NotificationDeliveryStatus.SENT,
                    invocation.getArgument(1),
                    null
            );
            return null;
        }).when(subscriberDeliveryRepository).markSent(anyLong(), any());
        doAnswer(invocation -> {
            updateSubscriberDelivery(
                    invocation.getArgument(0),
                    NotificationDeliveryStatus.FAILED,
                    null,
                    invocation.getArgument(1)
            );
            return null;
        }).when(subscriberDeliveryRepository).markFailed(anyLong(), any());
    }

    @Test
    void sendsThreeSeparateEmailsAndMarksEventSent() {
        NotificationDelivery event = event(1L, "notice-new", NoticeChangeType.NEW, 'a');
        arrangeEvents(List.of(event));
        List<NotificationSubscriber> subscribers = List.of(
                subscriber(11L, "one@example.com", true),
                subscriber(12L, "two@example.com", true),
                subscriber(13L, "three@example.com", true)
        );
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE)).thenReturn(subscribers);

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(3, result.sentCount());
        verify(emailSender).send(eq("one@example.com"), any());
        verify(emailSender).send(eq("two@example.com"), any());
        verify(emailSender).send(eq("three@example.com"), any());
        verify(deliveryRepository).markSent(eq(1L), any());
        verify(deliveryRepository, never()).markFailed(anyLong(), any());
    }

    @Test
    void oneSubscriberFailureDoesNotStopRemainingSubscribers() {
        NotificationDelivery event = event(2L, "notice-failure", NoticeChangeType.NEW, 'b');
        arrangeEvents(List.of(event));
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE)).thenReturn(List.of(
                subscriber(21L, "first@example.com", true),
                subscriber(22L, "failed@example.com", true),
                subscriber(23L, "last@example.com", true)
        ));
        doThrow(new IllegalStateException("credential-like-sensitive-value"))
                .when(emailSender).send(eq("failed@example.com"), any());

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(2, result.sentCount());
        assertEquals(1, result.failedCount());
        verify(emailSender).send(eq("last@example.com"), any());
        verify(deliveryRepository).markFailed(2L, "One or more subscriber deliveries failed");
        assertEquals(
                NotificationDeliveryStatus.FAILED,
                subscriberDeliveries.get("2:22").getStatus()
        );
        assertEquals(
                "Email delivery failed (IllegalStateException)",
                subscriberDeliveries.get("2:22").getFailureReason()
        );
    }

    @Test
    void oneSubscriberReservationFailureDoesNotStopRemainingSubscribers() {
        NotificationDelivery event = event(7L, "notice-reservation-failure", NoticeChangeType.NEW, 'g');
        arrangeEvents(List.of(event));
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE)).thenReturn(List.of(
                subscriber(71L, "first-db@example.com", true),
                subscriber(72L, "failed-db@example.com", true),
                subscriber(73L, "last-db@example.com", true)
        ));
        when(subscriberDeliveryRepository.createPendingIfAbsent(eq(7L), eq(72L), any()))
                .thenThrow(new IllegalStateException("temporary DB failure"));

        NotificationDispatchResult result = dispatcher(true).dispatchPending();

        assertEquals(2, result.sentCount());
        assertEquals(1, result.failedCount());
        verify(emailSender).send(eq("last-db@example.com"), any());
        verify(deliveryRepository).markFailed(7L, "One or more subscriber deliveries failed");
    }

    @Test
    void sameEventAndSubscriberIsNeverSentTwice() {
        NotificationDelivery event = event(3L, "notice-once", NoticeChangeType.NEW, 'c');
        arrangeEvents(List.of(event));
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(subscriber(31L, "once@example.com", true)));
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        verify(emailSender).send(eq("once@example.com"), any());
        assertEquals(1, subscriberDeliveries.size());
    }

    @Test
    void updatedFingerprintCreatesAnotherEventForTheSameSubscriber() {
        NotificationDelivery first = event(4L, "notice-updated", NoticeChangeType.NEW, 'd');
        NotificationDelivery updated = event(5L, "notice-updated", NoticeChangeType.UPDATED, 'e');
        when(deliveryRepository.findPending(NotificationChannel.EMAIL, NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(first))
                .thenReturn(List.of(updated));
        when(noticeRepository.findByExternalId("notice-updated"))
                .thenReturn(Optional.of(notice("notice-updated")));
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(List.of(subscriber(41L, "updates@example.com", true)));
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.dispatchPending();
        dispatcher.dispatchPending();

        verify(emailSender, times(2)).send(eq("updates@example.com"), any());
        assertEquals(2, subscriberDeliveries.size());
    }

    @Test
    void noActiveSubscribersLeavesEventPendingWithoutSending() {
        arrangeEvents(List.of(event(6L, "notice-no-subscriber", NoticeChangeType.NEW, 'f')));
        when(subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE)).thenReturn(List.of());

        dispatcher(true).dispatchPending();

        verifyNoInteractions(emailSender);
        verify(deliveryRepository, never()).markSent(anyLong(), any());
        verify(deliveryRepository, never()).markFailed(anyLong(), any());
    }

    @Test
    void disabledMailDoesNotQuerySendOrChangeAnyStatus() {
        NotificationDispatchResult result = dispatcher(false).dispatchPending();

        assertEquals(NotificationDispatchResult.disabledResult(), result);
        verifyNoInteractions(
                deliveryRepository,
                subscriberDeliveryRepository,
                subscriberService,
                noticeRepository,
                emailSender
        );
    }

    private void arrangeEvents(List<NotificationDelivery> events) {
        when(deliveryRepository.findPending(NotificationChannel.EMAIL, NotificationType.PIA_EXTERNAL_NOTICE))
                .thenReturn(events);
        for (NotificationDelivery event : events) {
            when(noticeRepository.findByExternalId(event.getExternalId()))
                    .thenReturn(Optional.of(notice(event.getExternalId())));
        }
    }

    private NotificationDispatcher dispatcher(boolean enabled) {
        return new NotificationDispatcher(
                deliveryRepository,
                subscriberDeliveryRepository,
                subscriberService,
                noticeRepository,
                emailSender,
                new ExternalNoticeEmailFactory(),
                properties(enabled),
                Clock.fixed(SENT_AT, ZoneOffset.UTC)
        );
    }

    private Optional<NotificationSubscriberDelivery> createSubscriberDelivery(
            Long eventId,
            Long subscriberId,
            Instant createdAt
    ) {
        String key = eventId + ":" + subscriberId;
        if (subscriberDeliveries.containsKey(key)) {
            return Optional.empty();
        }
        NotificationSubscriberDelivery delivery = NotificationSubscriberDelivery.builder()
                .id(subscriberDeliveryIds.incrementAndGet())
                .notificationDeliveryId(eventId)
                .subscriberId(subscriberId)
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(createdAt)
                .build();
        subscriberDeliveries.put(key, delivery);
        return Optional.of(delivery);
    }

    private void updateSubscriberDelivery(
            Long id,
            NotificationDeliveryStatus status,
            Instant sentAt,
            String failureReason
    ) {
        Map.Entry<String, NotificationSubscriberDelivery> entry = subscriberDeliveries.entrySet().stream()
                .filter(candidate -> candidate.getValue().getId().equals(id))
                .findFirst()
                .orElseThrow();
        NotificationSubscriberDelivery current = entry.getValue();
        entry.setValue(NotificationSubscriberDelivery.builder()
                .id(current.getId())
                .notificationDeliveryId(current.getNotificationDeliveryId())
                .subscriberId(current.getSubscriberId())
                .status(status)
                .createdAt(current.getCreatedAt())
                .sentAt(sentAt)
                .failureReason(failureReason)
                .build());
    }

    private BizAssistMailProperties properties(boolean enabled) {
        return new BizAssistMailProperties(
                enabled,
                "smtp.example.com",
                587,
                "username",
                "password",
                "sender@example.com",
                true,
                true,
                false,
                5_000,
                10_000,
                10_000
        );
    }

    private NotificationSubscriber subscriber(Long id, String email, boolean enabled) {
        return NotificationSubscriber.builder()
                .id(id)
                .email(email)
                .name("신청자 " + id)
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .enabled(enabled)
                .createdAt(SENT_AT.minusSeconds(100))
                .updatedAt(SENT_AT.minusSeconds(100))
                .build();
    }

    private NotificationDelivery event(
            Long id,
            String externalId,
            NoticeChangeType changeType,
            char fingerprintCharacter
    ) {
        return NotificationDelivery.builder()
                .id(id)
                .channel(NotificationChannel.EMAIL)
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .changeType(changeType)
                .contentFingerprint(String.valueOf(fingerprintCharacter).repeat(64))
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(SENT_AT.minusSeconds(60))
                .build();
    }

    private ExternalNotice notice(String externalId) {
        return ExternalNotice.builder()
                .id(100L)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .sourceNoticeId(externalId)
                .title("PIA 중요공지")
                .publishedDate(LocalDate.of(2026, 9, 1))
                .detailUrl("https://example.com/notices/" + externalId)
                .piaRelated(true)
                .matchedKeywords(List.of("개인정보 영향평가", "전문교육"))
                .fingerprint("z".repeat(64))
                .build();
    }
}
