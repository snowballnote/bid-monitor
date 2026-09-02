package com.comhu.bidmonitor.notification.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 명시적인 opt-in 환경변수가 있을 때만 실제 SMTP 서버로 한 건을 보내는 수동 검증 테스트다. */
class SmtpEmailSenderLiveTests {

    @Test
    @EnabledIfEnvironmentVariable(named = "BIZ_ASSIST_SMTP_LIVE_TEST", matches = "true")
    void sendsOneRealEmailUsingEnvironmentOnly() {
        BizAssistMailProperties properties = new BizAssistMailProperties(
                true,
                required("BIZ_ASSIST_MAIL_HOST"),
                Integer.parseInt(environment("BIZ_ASSIST_MAIL_PORT", "587")),
                required("BIZ_ASSIST_MAIL_USERNAME"),
                required("BIZ_ASSIST_MAIL_PASSWORD"),
                required("BIZ_ASSIST_MAIL_FROM"),
                required("BIZ_ASSIST_MAIL_TO"),
                Boolean.parseBoolean(environment("BIZ_ASSIST_MAIL_AUTH", "true")),
                Boolean.parseBoolean(environment("BIZ_ASSIST_MAIL_STARTTLS_ENABLED", "true")),
                Boolean.parseBoolean(environment("BIZ_ASSIST_MAIL_SSL_ENABLED", "false")),
                5_000,
                10_000,
                10_000
        );
        NotificationMailConfiguration configuration = new NotificationMailConfiguration();
        SmtpEmailSender sender = new SmtpEmailSender(
                configuration.bizAssistJavaMailSender(properties),
                properties
        );

        sender.send(new EmailMessage(
                "[Biz Assist] SMTP 연결 테스트",
                "Biz Assist 실제 SMTP 수동 검증 메일입니다.\n\nBiz Assist"
        ));
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다.");
        }
        return value;
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
