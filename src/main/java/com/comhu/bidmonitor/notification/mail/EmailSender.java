package com.comhu.bidmonitor.notification.mail;

/** SMTP 등 실제 전송 기술을 dispatcher와 분리하는 계약이다. */
public interface EmailSender {

    void send(String recipient, EmailMessage message);
}
