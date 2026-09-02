package com.comhu.bidmonitor.notification.subscriber.service;

/** 비활성화할 신청자 ID가 존재하지 않을 때 사용한다. */
public class NotificationSubscriberNotFoundException extends RuntimeException {

    public NotificationSubscriberNotFoundException(Long id) {
        super("알림 신청자를 찾을 수 없습니다: " + id);
    }
}
