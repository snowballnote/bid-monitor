package com.comhu.bidmonitor.notification.subscriber.service;

/** 신청 입력이 저장 가능한 이메일·이름 형식을 만족하지 못했음을 나타낸다. */
public class InvalidNotificationSubscriberException extends RuntimeException {

    public InvalidNotificationSubscriberException(String message) {
        super(message);
    }
}
