package com.comhu.bidmonitor.notification.mail;

import java.util.Objects;

/** 발송 기술과 분리된 단순 텍스트 이메일 메시지다. */
public record EmailMessage(String subject, String body) {

    public EmailMessage {
        Objects.requireNonNull(subject, "이메일 제목은 null일 수 없습니다.");
        Objects.requireNonNull(body, "이메일 본문은 null일 수 없습니다.");
    }
}
