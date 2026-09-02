package com.comhu.bidmonitor.notification.subscriber.api.dto;

import lombok.Builder;
import lombok.Value;

/** 신청자 관리 API의 예측 가능한 오류 응답이다. */
@Value
@Builder
public class NotificationSubscriberErrorResponse {

    int status;
    String error;
    String message;
}
