package com.comhu.bidmonitor.notification.subscriber.api.dto;

import com.comhu.bidmonitor.notification.model.NotificationType;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 내부 관리 API가 받는 최소 알림 신청 정보다. */
@Data
@NoArgsConstructor
public class CreateNotificationSubscriberRequest {

    private String email;
    private String name;
    private NotificationType notificationType;
}
