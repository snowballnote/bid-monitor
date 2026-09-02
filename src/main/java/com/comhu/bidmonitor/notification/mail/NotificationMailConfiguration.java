package com.comhu.bidmonitor.notification.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** SMTP 객체를 애플리케이션 설정으로 구성하되 네트워크 연결은 실제 send 시점까지 수행하지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BizAssistMailProperties.class)
public class NotificationMailConfiguration {

    @Bean
    JavaMailSender bizAssistJavaMailSender(BizAssistMailProperties mailProperties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(nullToEmpty(mailProperties.host()));
        sender.setPort(mailProperties.port());
        sender.setUsername(nullToEmpty(mailProperties.username()));
        sender.setPassword(nullToEmpty(mailProperties.password()));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.setProperty("mail.smtp.auth", Boolean.toString(mailProperties.auth()));
        javaMailProperties.setProperty(
                "mail.smtp.starttls.enable",
                Boolean.toString(mailProperties.starttlsEnabled())
        );
        javaMailProperties.setProperty("mail.smtp.ssl.enable", Boolean.toString(mailProperties.sslEnabled()));
        javaMailProperties.setProperty(
                "mail.smtp.connectiontimeout",
                Integer.toString(mailProperties.connectionTimeout())
        );
        javaMailProperties.setProperty("mail.smtp.timeout", Integer.toString(mailProperties.readTimeout()));
        javaMailProperties.setProperty("mail.smtp.writetimeout", Integer.toString(mailProperties.writeTimeout()));
        return sender;
    }

    @Bean
    EmailSender smtpEmailSender(JavaMailSender mailSender, BizAssistMailProperties mailProperties) {
        return new SmtpEmailSender(mailSender, mailProperties);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
