package com.comhu.bidmonitor.notification.dispatch;

import com.comhu.bidmonitor.externalnotice.change.ExternalNoticeChangeService;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.mail.BizAssistMailProperties;
import com.comhu.bidmonitor.notification.mail.EmailMessage;
import com.comhu.bidmonitor.notification.mail.ExternalNoticeEmailFactory;
import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import com.comhu.bidmonitor.notification.service.ExternalNoticeNotificationService;
import com.comhu.bidmonitor.notification.subscriber.model.NotificationSubscriber;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDelivery;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 현재 환경의 SMTP 실발송을 신청자 API부터 수신자별 이력까지 한 건으로 검증하는 명시적 opt-in 테스트다.
 * 별도 인메모리 DB와 고유 fixture source를 사용해 운영 baseline/공지/발송 이력을 건드리지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-subscriber-smtp-live;DB_CLOSE_DELAY=-1",
        "external-notice.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BIZ_ASSIST_SUBSCRIBER_SMTP_LIVE_TEST", matches = "true")
class NotificationSubscriberSmtpLiveIntegrationTests {

    private static final String SOURCE_CODE = "SMTP_LIVE_FIXTURE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExternalNoticeChangeService changeService;

    @Autowired
    private ExternalNoticeNotificationService notificationService;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private NotificationSubscriberService subscriberService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationSubscriberDeliveryRepository subscriberDeliveryRepository;

    @Autowired
    private ExternalNoticeRepository noticeRepository;

    @Autowired
    private ExternalNoticeEmailFactory emailFactory;

    @Autowired
    private BizAssistMailProperties mailProperties;

    @Test
    void sendsExactlyOneNewPiaNotificationAndPreventsDuplicateDispatch() throws Exception {
        LiveInputs inputs = validateLiveInputs();
        registerSubscriberThroughApi(inputs.recipient());

        List<NotificationSubscriber> subscribers = subscriberService.findEnabled(NotificationType.PIA_EXTERNAL_NOTICE);
        assertEquals(1, subscribers.size(), "격리 DB에는 테스트 신청자 한 명만 있어야 합니다.");
        NotificationSubscriber subscriber = subscribers.getFirst();

        // 동일 source의 첫 자동 실행은 baseline만 완료하고 메일 후보를 만들지 않는 기존 정책을 확인한다.
        NoticeChangeResult baselineChange = changeService.process(notice(
                "baseline-" + inputs.runToken(),
                "baseline-" + inputs.runToken(),
                "PIA SMTP baseline fixture"
        ));
        assertTrue(notificationService.createCandidates(collectionResult(baselineChange)).isEmpty());
        assertTrue(deliveryRepository.findAll().isEmpty());

        String externalId = "smtp-live-" + inputs.runToken();
        String fingerprint = sha256("new:" + inputs.runToken());
        ExternalNotice testNotice = notice(externalId, fingerprint, "Biz Assist PIA 알림 SMTP 통합 테스트");
        NoticeChangeResult newChange = changeService.process(testNotice);
        assertEquals(NoticeChangeType.NEW, newChange.getChangeType());

        List<ExternalNoticeNotificationCandidate> candidates =
                notificationService.createCandidates(collectionResult(newChange));
        assertEquals(1, candidates.size());
        Long deliveryId = candidates.getFirst().getDeliveryId();
        NotificationDelivery pending = findDelivery(deliveryId);
        assertEquals(NotificationDeliveryStatus.PENDING, pending.getStatus());

        ExternalNotice persistedNotice = noticeRepository.findByExternalId(externalId).orElseThrow();
        EmailMessage message = emailFactory.create(pending, persistedNotice);
        System.out.println("LIVE_SMTP_RECIPIENT=" + maskEmail(subscriber.getEmail()));
        System.out.println("LIVE_SMTP_SUBJECT=" + message.subject());
        System.out.println("LIVE_NOTIFICATION_DELIVERY_PENDING_ID=" + deliveryId);

        NotificationDispatchResult firstDispatch = dispatcher.dispatchPending();
        assertFalse(firstDispatch.disabled());
        assertEquals(1, firstDispatch.pendingCount());
        assertEquals(1, firstDispatch.sentCount());
        assertEquals(0, firstDispatch.failedCount());

        NotificationDelivery sentEvent = findDelivery(deliveryId);
        List<NotificationSubscriberDelivery> subscriberDeliveries =
                subscriberDeliveryRepository.findByNotificationDeliveryId(deliveryId);
        assertEquals(NotificationDeliveryStatus.SENT, sentEvent.getStatus());
        assertEquals(1, subscriberDeliveries.size());
        NotificationSubscriberDelivery subscriberDelivery = subscriberDeliveries.getFirst();
        assertEquals(NotificationDeliveryStatus.SENT, subscriberDelivery.getStatus());

        NotificationDispatchResult duplicateDispatch = dispatcher.dispatchPending();
        assertEquals(0, duplicateDispatch.pendingCount());
        assertEquals(0, duplicateDispatch.sentCount());
        assertEquals(0, duplicateDispatch.failedCount());
        assertEquals(1, subscriberDeliveryRepository.findByNotificationDeliveryId(deliveryId).size());

        System.out.println("LIVE_NOTIFICATION_DELIVERY_ID=" + deliveryId);
        System.out.println("LIVE_SUBSCRIBER_DELIVERY_ID=" + subscriberDelivery.getId());
        System.out.println("LIVE_NOTIFICATION_DELIVERY_STATUS=" + sentEvent.getStatus());
        System.out.println("LIVE_SUBSCRIBER_DELIVERY_STATUS=" + subscriberDelivery.getStatus());
        System.out.println("LIVE_DUPLICATE_SEND_PREVENTED=true");
    }

    private void registerSubscriberThroughApi(String recipient) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "email", recipient,
                "name", "테스트 사용자",
                "notificationType", "PIA_EXTERNAL_NOTICE"
        ));
        mockMvc.perform(post("/api/notification-subscribers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    private LiveInputs validateLiveInputs() {
        String recipient = required("BIZ_ASSIST_LIVE_SUBSCRIBER_EMAIL").trim();
        String runToken = required("BIZ_ASSIST_LIVE_RUN_TOKEN").trim();
        if (!runToken.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new IllegalStateException("BIZ_ASSIST_LIVE_RUN_TOKEN은 8~80자의 영문/숫자/_/-만 허용합니다.");
        }
        if (!mailProperties.enabled()) {
            throw new IllegalStateException("BIZ_ASSIST_MAIL_ENABLED=true가 필요합니다.");
        }
        required("BIZ_ASSIST_MAIL_HOST");
        required("BIZ_ASSIST_MAIL_PORT");
        required("BIZ_ASSIST_MAIL_USERNAME");
        required("BIZ_ASSIST_MAIL_PASSWORD");
        required("BIZ_ASSIST_MAIL_FROM");
        return new LiveInputs(recipient, runToken);
    }

    private ExternalNotice notice(String externalId, String fingerprintSeed, String title) {
        Instant now = Instant.now();
        return ExternalNotice.builder()
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .sourceNoticeId(externalId)
                .title(title)
                .publishedDate(LocalDate.now())
                .detailUrl(null)
                .body("실제 포털 공지를 사용하지 않는 단일 SMTP 통합 테스트 fixture")
                .piaRelated(true)
                .matchedKeyword("개인정보 영향평가")
                .classificationReason("SMTP live integration fixture")
                .fingerprint(sha256(fingerprintSeed))
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    private ExternalNoticeCollectionResult collectionResult(NoticeChangeResult result) {
        return ExternalNoticeCollectionResult.builder()
                .collectedCount(1)
                .newCount(result.getChangeType() == NoticeChangeType.NEW ? 1 : 0)
                .updatedCount(result.getChangeType() == NoticeChangeType.UPDATED ? 1 : 0)
                .unchangedCount(result.getChangeType() == NoticeChangeType.UNCHANGED ? 1 : 0)
                .piaRelatedCount(1)
                .noticeResult(result)
                .build();
    }

    private NotificationDelivery findDelivery(Long id) {
        return deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다.");
        }
        return value;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("테스트 fingerprint 생성 실패", exception);
        }
    }

    private record LiveInputs(String recipient, String runToken) {
    }
}
