package com.comhu.bidmonitor.notification.subscriber.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 이벤트·신청자 조합을 한 번만 예약하고 신청자별 결과를 기록하는 계약이다. */
public interface NotificationSubscriberDeliveryRepository {

    Optional<NotificationSubscriberDelivery> createPendingIfAbsent(
            Long notificationDeliveryId,
            Long subscriberId,
            Instant createdAt
    );

    List<NotificationSubscriberDelivery> findByNotificationDeliveryId(Long notificationDeliveryId);

    void markSent(Long id, Instant sentAt);

    void markFailed(Long id, String failureReason);
}
