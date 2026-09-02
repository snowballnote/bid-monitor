package com.comhu.bidmonitor.notification.subscriber.persistence;

import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** 하나의 알림 이벤트를 특정 신청자에게 보낸 결과를 독립적으로 추적한다. */
@Value
@Builder
public class NotificationSubscriberDelivery {

    Long id;
    Long notificationDeliveryId;
    Long subscriberId;
    NotificationDeliveryStatus status;
    Instant createdAt;
    Instant sentAt;
    String failureReason;
}
