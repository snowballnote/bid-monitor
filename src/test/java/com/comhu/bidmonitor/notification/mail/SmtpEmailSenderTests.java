package com.comhu.bidmonitor.notification.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailSenderTests {

    @Test
    void mapsConfiguredSenderRecipientsAndPlainTextMessage() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        BizAssistMailProperties properties = new BizAssistMailProperties(
                true,
                "smtp.example.com",
                587,
                "user",
                "password",
                "sender@example.com",
                "first@example.com, second@example.com",
                true,
                true,
                false,
                5_000,
                10_000,
                10_000
        );
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, properties);

        sender.send(new EmailMessage("테스트 제목", "테스트 본문"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertEquals("sender@example.com", sent.getFrom());
        assertArrayEquals(
                new String[]{"first@example.com", "second@example.com"},
                sent.getTo()
        );
        assertEquals("테스트 제목", sent.getSubject());
        assertEquals("테스트 본문", sent.getText());
    }
}
