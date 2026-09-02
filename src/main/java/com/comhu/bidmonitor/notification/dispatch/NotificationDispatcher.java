package com.comhu.bidmonitor.notification.dispatch;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.mail.BizAssistMailProperties;
import com.comhu.bidmonitor.notification.mail.EmailMessage;
import com.comhu.bidmonitor.notification.mail.EmailSender;
import com.comhu.bidmonitor.notification.mail.ExternalNoticeEmailFactory;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** PIA 외부공지의 PENDING 이메일 이력을 독립적으로 발송하고 결과 상태를 기록한다. */
@Slf4j
@Service
public class NotificationDispatcher {

    private static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;
    private static final NotificationType TYPE = NotificationType.PIA_EXTERNAL_NOTICE;

    private final NotificationDeliveryRepository deliveryRepository;
    private final ExternalNoticeRepository noticeRepository;
    private final EmailSender emailSender;
    private final ExternalNoticeEmailFactory emailFactory;
    private final BizAssistMailProperties mailProperties;
    private final Clock clock;

    public NotificationDispatcher(
            NotificationDeliveryRepository deliveryRepository,
            ExternalNoticeRepository noticeRepository,
            EmailSender emailSender,
            ExternalNoticeEmailFactory emailFactory,
            BizAssistMailProperties mailProperties,
            Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
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
        int sentCount = 0;
        int failedCount = 0;
        for (NotificationDelivery delivery : pendingDeliveries) {
            try {
                ExternalNotice notice = loadMatchingPiaNotice(delivery);
                EmailMessage message = emailFactory.create(delivery, notice);
                emailSender.send(message);
                deliveryRepository.markSent(delivery.getId(), clock.instant());
                sentCount++;
            } catch (RuntimeException exception) {
                failedCount++;
                recordFailure(delivery, exception);
            }
        }

        log.info(
                "PIA 이메일 알림 처리 완료: pendingCount={}, sentCount={}, failedCount={}",
                pendingDeliveries.size(),
                sentCount,
                failedCount
        );
        return new NotificationDispatchResult(false, pendingDeliveries.size(), sentCount, failedCount);
    }

    private ExternalNotice loadMatchingPiaNotice(NotificationDelivery delivery) {
        ExternalNotice notice = noticeRepository.findByExternalId(delivery.getExternalId())
                .orElseThrow(() -> new IllegalStateException("알림 대상 외부공지를 찾을 수 없습니다."));
        if (!Objects.equals(delivery.getSourceCode(), notice.getSourceCode()) || !notice.isPiaRelated()) {
            throw new IllegalStateException("알림 이력과 외부공지 정보가 일치하지 않습니다.");
        }
        return notice;
    }

    private void recordFailure(NotificationDelivery delivery, RuntimeException exception) {
        String safeReason = "Email delivery failed (" + exception.getClass().getSimpleName() + ")";
        try {
            deliveryRepository.markFailed(delivery.getId(), safeReason);
        } catch (RuntimeException statusException) {
            log.error(
                    "PIA 이메일 알림 실패 상태 기록 불가: deliveryId={}",
                    delivery.getId()
            );
        }
        RuntimeException sanitizedLogException = new RuntimeException(safeReason);
        sanitizedLogException.setStackTrace(exception.getStackTrace());
        log.error(
                "PIA 이메일 알림 발송 실패: deliveryId={}, externalId={}",
                delivery.getId(),
                delivery.getExternalId(),
                sanitizedLogException
        );
    }
}
