package com.comhu.bidmonitor.notification.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 설정된 SMTP 서버를 통해 UTF-8 plain-text 메일을 발송한다. */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final BizAssistMailProperties properties;

    public SmtpEmailSender(JavaMailSender mailSender, BizAssistMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String recipient, EmailMessage message) {
        String from = requireConfigured(properties.from());
        String recipientAddress = requireConfigured(recipient);

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        // 개인정보 노출을 막기 위해 dispatcher가 전달한 신청자 한 명만 To로 설정한다.
        mail.setTo(recipientAddress);
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
    }

    private String requireConfigured(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 메일 설정이 누락되었습니다.");
        }
        return value.trim();
    }
}
