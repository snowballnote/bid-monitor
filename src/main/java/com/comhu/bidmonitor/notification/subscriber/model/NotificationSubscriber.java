package com.comhu.bidmonitor.notification.subscriber.model;

import com.comhu.bidmonitor.notification.model.NotificationType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** 알림 유형별 이메일 신청 상태와 생성·변경 시각을 보관하는 영속 모델이다. */
@Value
@Builder
public class NotificationSubscriber {

    Long id;
    String email;
    String name;
    NotificationType notificationType;
    boolean enabled;
    Instant createdAt;
    Instant updatedAt;
}
