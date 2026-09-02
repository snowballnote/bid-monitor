package com.comhu.bidmonitor.notification.subscriber.service;

/** 같은 이메일과 알림 유형의 중복 등록을 명확한 API 오류로 전달한다. */
public class DuplicateNotificationSubscriberException extends RuntimeException {

    public DuplicateNotificationSubscriberException() {
        super("이미 등록된 이메일과 알림 유형입니다.");
    }
}
