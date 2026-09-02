package com.comhu.bidmonitor.notification.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 환경변수 또는 외부 설정에서만 주입되는 Biz Assist SMTP 설정이다. */
@ConfigurationProperties(prefix = "biz-assist.mail")
public record BizAssistMailProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        String from,
        boolean auth,
        boolean starttlsEnabled,
        boolean sslEnabled,
        int connectionTimeout,
        int readTimeout,
        int writeTimeout
) {
}
