package com.comhu.bidmonitor.notification.persistence;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** 전송 구현이 나중에 PENDING을 처리하고 결과를 갱신할 수 있도록 보관하는 이력 모델이다. */
@Value
@Builder
public class NotificationDelivery {

    Long id;
    NotificationChannel channel;
    NotificationType notificationType;
    String sourceCode;
    String externalId;
    NoticeChangeType changeType;
    String contentFingerprint;
    NotificationDeliveryStatus status;
    Instant createdAt;
    Instant sentAt;
    String failureReason;
}
