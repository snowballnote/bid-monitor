package com.comhu.bidmonitor.notification.persistence;

import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 후보 예약과 향후 전송 결과 기록을 저장 기술과 분리한 계약이다. */
public interface NotificationDeliveryRepository {

    Optional<NotificationDelivery> createPendingIfAbsent(NotificationDelivery delivery);

    List<NotificationDelivery> findPending(
            NotificationChannel channel,
            NotificationType notificationType
    );

    void markSent(Long deliveryId, Instant sentAt);

    void markFailed(Long deliveryId, String failureReason);

    List<NotificationDelivery> findAll();
}
