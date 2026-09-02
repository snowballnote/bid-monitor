package com.comhu.bidmonitor.notification.persistence.jdbc;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationBaselineRepository;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class NotificationPersistenceTests {

    private static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;
    private static final NotificationType TYPE = NotificationType.PIA_EXTERNAL_NOTICE;
    private static final String SOURCE_CODE = "PRIVACY_PORTAL";
    private static final Instant DETECTED_AT = Instant.parse("2026-09-02T01:02:03Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationBaselineRepository baselineRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Test
    void baselineRemainsCompletedWhenRepositoryIsRecreated() {
        assertTrue(baselineRepository.completeIfAbsent(CHANNEL, TYPE, SOURCE_CODE, DETECTED_AT));

        NotificationBaselineRepository repositoryAfterRestart =
                new JdbcNotificationBaselineRepository(jdbcTemplate);

        assertTrue(repositoryAfterRestart.isCompleted(CHANNEL, TYPE, SOURCE_CODE));
        assertFalse(repositoryAfterRestart.completeIfAbsent(CHANNEL, TYPE, SOURCE_CODE, DETECTED_AT.plusSeconds(60)));
    }

    @Test
    void identicalDeliveryKeyIsReservedOnlyOnce() {
        NotificationDelivery delivery = pendingDelivery("external-1", fingerprint('a'), NoticeChangeType.NEW);

        assertTrue(deliveryRepository.createPendingIfAbsent(delivery).isPresent());
        assertTrue(deliveryRepository.createPendingIfAbsent(delivery).isEmpty());
        assertEquals(1, deliveryRepository.findAll().size());
    }

    @Test
    void aNewFingerprintCanBeReservedForTheSameExternalNotice() {
        assertTrue(deliveryRepository.createPendingIfAbsent(
                pendingDelivery("external-2", fingerprint('b'), NoticeChangeType.NEW)
        ).isPresent());
        assertTrue(deliveryRepository.createPendingIfAbsent(
                pendingDelivery("external-2", fingerprint('c'), NoticeChangeType.UPDATED)
        ).isPresent());

        assertEquals(2, deliveryRepository.findAll().size());
    }

    @Test
    void deliveryStatusCanBeMarkedSentOrFailed() {
        NotificationDelivery sent = deliveryRepository.createPendingIfAbsent(
                pendingDelivery("external-3", fingerprint('d'), NoticeChangeType.NEW)
        ).orElseThrow();
        NotificationDelivery failed = deliveryRepository.createPendingIfAbsent(
                pendingDelivery("external-4", fingerprint('e'), NoticeChangeType.UPDATED)
        ).orElseThrow();
        Instant sentAt = DETECTED_AT.plusSeconds(30);

        deliveryRepository.markSent(sent.getId(), sentAt);
        deliveryRepository.markFailed(failed.getId(), "test failure");

        NotificationDelivery sentResult = deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getId().equals(sent.getId()))
                .findFirst()
                .orElseThrow();
        NotificationDelivery failedResult = deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getId().equals(failed.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(NotificationDeliveryStatus.SENT, sentResult.getStatus());
        assertEquals(sentAt, sentResult.getSentAt());
        assertEquals(NotificationDeliveryStatus.FAILED, failedResult.getStatus());
        assertEquals("test failure", failedResult.getFailureReason());
        assertNotNull(sent.getId());
    }

    private NotificationDelivery pendingDelivery(
            String externalId,
            String contentFingerprint,
            NoticeChangeType changeType
    ) {
        return NotificationDelivery.builder()
                .channel(CHANNEL)
                .notificationType(TYPE)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .changeType(changeType)
                .contentFingerprint(contentFingerprint)
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(DETECTED_AT)
                .build();
    }

    private String fingerprint(char character) {
        return String.valueOf(character).repeat(64);
    }
}
