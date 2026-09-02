package com.comhu.bidmonitor.notification.dispatch;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.mail.BizAssistMailProperties;
import com.comhu.bidmonitor.notification.mail.EmailMessage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** PIA 알림 이벤트를 활성 신청자별로 개별 발송하고 두 단계의 상태를 집계한다. */
@Slf4j
@Service
public class NotificationDispatcher {

    private static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;
    private static final NotificationType TYPE = NotificationType.PIA_EXTERNAL_NOTICE;

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSubscriberDeliveryRepository subscriberDeliveryRepository;
    private final NotificationSubscriberService subscriberService;
    private final ExternalNoticeRepository noticeRepository;
    private final EmailSender emailSender;
    private final ExternalNoticeEmailFactory emailFactory;
    private final BizAssistMailProperties mailProperties;
    private final Clock clock;

    public NotificationDispatcher(
            NotificationDeliveryRepository deliveryRepository,
            NotificationSubscriberDeliveryRepository subscriberDeliveryRepository,
            NotificationSubscriberService subscriberService,
            ExternalNoticeRepository noticeRepository,
            EmailSender emailSender,
            ExternalNoticeEmailFactory emailFactory,
            BizAssistMailProperties mailProperties,
            Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.subscriberDeliveryRepository = subscriberDeliveryRepository;
        this.subscriberService = subscriberService;
        this.noticeRepository = noticeRepository;
        this.emailSender = emailSender;
        this.emailFactory = emailFactory;
        this.mailProperties = mailProperties;
        this.clock = clock;
    }

    public NotificationDispatchResult dispatchPending() {
        if (!mailProperties.enabled()) {
            return NotificationDispatchResult.disabledResult();
        }

        List<NotificationDelivery> pendingDeliveries = deliveryRepository.findPending(CHANNEL, TYPE);
        List<NotificationSubscriber> subscribers = subscriberService.findEnabled(TYPE);
        int sentCount = 0;
        int failedCount = 0;

        for (NotificationDelivery delivery : pendingDeliveries) {
            try {
                ExternalNotice notice = loadMatchingPiaNotice(delivery);
                EmailMessage message = emailFactory.create(delivery, notice);
                boolean subscriberFailure = false;
                for (NotificationSubscriber subscriber : subscribers) {
                    DispatchCount count = dispatchToSubscriber(delivery, subscriber, message);
                    sentCount += count.sentCount();
                    failedCount += count.failedCount();
                    subscriberFailure |= count.failedCount() > 0;
                }
                aggregateEventStatus(delivery, subscriberFailure);
            } catch (RuntimeException exception) {
                failedCount++;
                recordEventFailure(delivery, exception);
            }
        }

        log.info(
                "PIA 이메일 알림 처리 완료: eventCount={}, subscriberCount={}, sentCount={}, failedCount={}",
                pendingDeliveries.size(),
                subscribers.size(),
                sentCount,
                failedCount
        );
        return new NotificationDispatchResult(false, pendingDeliveries.size(), sentCount, failedCount);
    }

    private DispatchCount dispatchToSubscriber(
            NotificationDelivery delivery,
            NotificationSubscriber subscriber,
            EmailMessage message
    ) {
        NotificationSubscriberDelivery subscriberDelivery = null;
        try {
            subscriberDelivery = subscriberDeliveryRepository
                    .createPendingIfAbsent(delivery.getId(), subscriber.getId(), clock.instant())
                    .orElse(null);
            if (subscriberDelivery == null) {
                return DispatchCount.NONE;
            }
            emailSender.send(subscriber.getEmail(), message);
            subscriberDeliveryRepository.markSent(subscriberDelivery.getId(), clock.instant());
            return DispatchCount.SENT;
        } catch (RuntimeException exception) {
            if (subscriberDelivery == null) {
                logSanitizedFailure(delivery, subscriber.getId(), safeReason(exception), exception);
            } else {
                recordSubscriberFailure(delivery, subscriber, subscriberDelivery, exception);
            }
            return DispatchCount.FAILED;
        }
    }

    /** 자식 상태가 모두 확정된 뒤 이벤트 outbox 상태를 한 번만 종결한다. */
    private void aggregateEventStatus(NotificationDelivery delivery, boolean subscriberFailure) {
        List<NotificationSubscriberDelivery> results = subscriberDeliveryRepository
                .findByNotificationDeliveryId(delivery.getId());
        if (subscriberFailure) {
            deliveryRepository.markFailed(delivery.getId(), "One or more subscriber deliveries failed");
            return;
        }
        if (results.isEmpty()) {
            // 활성 신청자가 없으면 나중에 신청자가 등록될 가능성을 위해 이벤트를 PENDING으로 둔다.
            return;
        }
        if (results.stream().allMatch(result -> result.getStatus() == NotificationDeliveryStatus.SENT)) {
            deliveryRepository.markSent(delivery.getId(), clock.instant());
        }
    }

    private ExternalNotice loadMatchingPiaNotice(NotificationDelivery delivery) {
        ExternalNotice notice = noticeRepository.findByExternalId(delivery.getExternalId())
                .orElseThrow(() -> new IllegalStateException("알림 대상 외부공지를 찾을 수 없습니다."));
        if (!Objects.equals(delivery.getSourceCode(), notice.getSourceCode()) || !notice.isPiaRelated()) {
            throw new IllegalStateException("알림 이력과 외부공지 정보가 일치하지 않습니다.");
        }
        return notice;
    }

    private void recordSubscriberFailure(
            NotificationDelivery delivery,
            NotificationSubscriber subscriber,
            NotificationSubscriberDelivery subscriberDelivery,
            RuntimeException exception
    ) {
        String safeReason = safeReason(exception);
        try {
            subscriberDeliveryRepository.markFailed(subscriberDelivery.getId(), safeReason);
        } catch (RuntimeException statusException) {
            log.error(
                    "신청자별 이메일 실패 상태 기록 불가: deliveryId={}, subscriberId={}",
                    delivery.getId(),
                    subscriber.getId()
            );
        }
        logSanitizedFailure(delivery, subscriber.getId(), safeReason, exception);
    }

    private void recordEventFailure(NotificationDelivery delivery, RuntimeException exception) {
        String safeReason = safeReason(exception);
        try {
            deliveryRepository.markFailed(delivery.getId(), safeReason);
        } catch (RuntimeException statusException) {
            log.error("PIA 이메일 이벤트 실패 상태 기록 불가: deliveryId={}", delivery.getId());
        }
        logSanitizedFailure(delivery, null, safeReason, exception);
    }

    private String safeReason(RuntimeException exception) {
        return "Email delivery failed (" + exception.getClass().getSimpleName() + ")";
    }

    private void logSanitizedFailure(
            NotificationDelivery delivery,
            Long subscriberId,
            String safeReason,
            RuntimeException exception
    ) {
        RuntimeException sanitized = new RuntimeException(safeReason);
        sanitized.setStackTrace(exception.getStackTrace());
        log.error(
                "PIA 이메일 알림 발송 실패: deliveryId={}, subscriberId={}",
                delivery.getId(),
                subscriberId,
                sanitized
        );
    }

    private record DispatchCount(int sentCount, int failedCount) {
        private static final DispatchCount NONE = new DispatchCount(0, 0);
        private static final DispatchCount SENT = new DispatchCount(1, 0);
        private static final DispatchCount FAILED = new DispatchCount(0, 1);
    }
}
