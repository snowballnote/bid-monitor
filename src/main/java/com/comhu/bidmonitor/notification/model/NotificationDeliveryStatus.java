package com.comhu.bidmonitor.notification.model;

/** 후보 예약부터 실제 발송 결과 기록까지의 최소 상태이다. */
public enum NotificationDeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
