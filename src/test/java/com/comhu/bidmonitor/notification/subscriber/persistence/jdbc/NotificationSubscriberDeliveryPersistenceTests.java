package com.comhu.bidmonitor.notification.subscriber.persistence.jdbc;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDelivery;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscriber-delivery-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class NotificationSubscriberDeliveryPersistenceTests {

    private static final Instant NOW = Instant.parse("2026-09-02T05:00:00Z");

    @Autowired
    private NotificationDeliveryRepository eventRepository;

    @Autowired
    private NotificationSubscriberRepository subscriberRepository;

    @Autowired
    private NotificationSubscriberDeliveryRepository subscriberDeliveryRepository;

    @Test
    void eventAndSubscriberPairCanBeReservedOnlyOnceAndTracksStatus() {
        NotificationDelivery event = eventRepository.createPendingIfAbsent(NotificationDelivery.builder()
                .channel(NotificationChannel.EMAIL)
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .sourceCode("PRIVACY_PORTAL")
                .externalId("subscriber-delivery-event")
                .changeType(NoticeChangeType.NEW)
                .contentFingerprint("a".repeat(64))
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(NOW)
                .build()).orElseThrow();
        NotificationSubscriber subscriber = subscriberRepository.save(NotificationSubscriber.builder()
                .email("subscriber@example.com")
                .name("신청자")
                .notificationType(NotificationType.PIA_EXTERNAL_NOTICE)
                .enabled(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());

        NotificationSubscriberDelivery reserved = subscriberDeliveryRepository.createPendingIfAbsent(
                event.getId(), subscriber.getId(), NOW
        ).orElseThrow();

        assertTrue(subscriberDeliveryRepository.createPendingIfAbsent(
                event.getId(), subscriber.getId(), NOW
        ).isEmpty());
        subscriberDeliveryRepository.markSent(reserved.getId(), NOW.plusSeconds(10));
        NotificationSubscriberDelivery result = subscriberDeliveryRepository
                .findByNotificationDeliveryId(event.getId()).getFirst();
        assertEquals(NotificationDeliveryStatus.SENT, result.getStatus());
        assertEquals(NOW.plusSeconds(10), result.getSentAt());
    }
}
