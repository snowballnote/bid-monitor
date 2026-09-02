package com.comhu.bidmonitor.notification.subscriber.service;

import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 이메일 정규화·검증과 신청자 등록·조회·비활성화 정책을 담당한다. */
@Service
public class NotificationSubscriberService {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_NAME_LENGTH = 200;

    private final NotificationSubscriberRepository repository;
    private final Clock clock;

    public NotificationSubscriberService(NotificationSubscriberRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public NotificationSubscriber register(
            String email,
            String name,
            NotificationType notificationType
    ) {
        String normalizedEmail = normalizeAndValidateEmail(email);
        String normalizedName = normalizeName(name);
        if (notificationType == null) {
            throw new InvalidNotificationSubscriberException("알림 유형은 필수입니다.");
        }
        Instant now = clock.instant();
        try {
            return repository.save(NotificationSubscriber.builder()
                    .email(normalizedEmail)
                    .name(normalizedName)
                    .notificationType(notificationType)
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DuplicateKeyException exception) {
            throw new DuplicateNotificationSubscriberException();
        }
    }

    public List<NotificationSubscriber> findAll() {
        return repository.findAll();
    }

    public List<NotificationSubscriber> findEnabled(NotificationType notificationType) {
        return repository.findEnabledByNotificationType(notificationType);
    }

    public NotificationSubscriber disable(Long id) {
        if (!repository.disable(id, clock.instant())) {
            throw new NotificationSubscriberNotFoundException(id);
        }
        return repository.findById(id).orElseThrow(() -> new NotificationSubscriberNotFoundException(id));
    }

    private String normalizeAndValidateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidNotificationSubscriberException("이메일은 필수입니다.");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || normalized.contains("\r") || normalized.contains("\n")) {
            throw new InvalidNotificationSubscriberException("올바른 이메일 형식이 아닙니다.");
        }
        try {
            InternetAddress address = new InternetAddress(normalized, true);
            address.validate();
            if (!normalized.equals(address.getAddress())) {
                throw new AddressException("display name is not allowed");
            }
        } catch (AddressException exception) {
            throw new InvalidNotificationSubscriberException("올바른 이메일 형식이 아닙니다.");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new InvalidNotificationSubscriberException("이름은 200자 이하여야 합니다.");
        }
        return normalized;
    }
}
