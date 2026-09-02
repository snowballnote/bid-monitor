package com.comhu.bidmonitor.notification.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Arrays;

/** 설정된 SMTP 서버를 통해 UTF-8 plain-text 메일을 발송한다. */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final BizAssistMailProperties properties;

    public SmtpEmailSender(JavaMailSender mailSender, BizAssistMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(EmailMessage message) {
        String from = requireConfigured(properties.from());
        String[] recipients = Arrays.stream(requireConfigured(properties.to()).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
        if (recipients.length == 0) {
            throw new IllegalStateException("메일 수신자 설정이 필요합니다.");
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipients);
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
