package com.comhu.bidmonitor.notification.subscriber.api.dto;

import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** 영속 모델을 직접 노출하지 않는 신청자 관리 API 응답이다. */
@Value
@Builder
public class NotificationSubscriberResponse {

    Long id;
    String email;
    String name;
    NotificationType notificationType;
    boolean enabled;
    Instant createdAt;
    Instant updatedAt;

    public static NotificationSubscriberResponse from(NotificationSubscriber subscriber) {
        return NotificationSubscriberResponse.builder()
                .id(subscriber.getId())
                .email(subscriber.getEmail())
                .name(subscriber.getName())
                .notificationType(subscriber.getNotificationType())
                .enabled(subscriber.isEnabled())
                .createdAt(subscriber.getCreatedAt())
                .updatedAt(subscriber.getUpdatedAt())
                .build();
    }
}
