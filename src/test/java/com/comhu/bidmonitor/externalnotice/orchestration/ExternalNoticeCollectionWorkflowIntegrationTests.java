package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.collector.ExternalNoticeCollector;
import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import com.comhu.bidmonitor.externalnotice.domain.NoticeSource;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.externalnotice.scheduler.ExternalNoticeScheduler;
import com.comhu.bidmonitor.notification.mail.BizAssistMailProperties;
import com.comhu.bidmonitor.notification.mail.EmailSender;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationBaselineRepository;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDelivery;
import com.comhu.bidmonitor.notification.subscriber.persistence.NotificationSubscriberDeliveryRepository;
import com.comhu.bidmonitor.notification.subscriber.service.NotificationSubscriberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 수동 API와 스케줄러가 동일한 알림 workflow 및 DB 중복 방지 장치를 사용하는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-workflow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=true",
        "external-notice.scheduler.fixed-delay-ms=86400000",
        "external-notice.scheduler.initial-delay-ms=86400000",
        "external-notice.scheduler.run-on-startup=false"
})
class ExternalNoticeCollectionWorkflowIntegrationTests {

    private static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;
    private static final NotificationType TYPE = NotificationType.PIA_EXTERNAL_NOTICE;
    private static final String SOURCE_CODE = NoticeSource.PRIVACY_PORTAL.getCode();
    private static final String EXTERNAL_ID = "PRIVACY_PORTAL:BOARD:manual-workflow-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExternalNoticeScheduler scheduler;

    @Autowired
    private ExternalNoticeRepository noticeRepository;

    @Autowired
    private NotificationBaselineRepository baselineRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationSubscriberDeliveryRepository subscriberDeliveryRepository;

    @Autowired
    private NotificationSubscriberService subscriberService;

    @MockitoBean
    private ExternalNoticeCollector collector;

    @MockitoBean
    private EmailSender emailSender;

    @MockitoBean
    private BizAssistMailProperties mailProperties;

    @BeforeEach
    void registerSubscriberAndEnableFakeMail() {
        subscriberService.register("workflow@example.com", "workflow tester", TYPE);
        when(mailProperties.enabled()).thenReturn(true);
    }

    @Test
    void completedBaselineThenManualNewCreatesEventAndSubscriberDelivery() throws Exception {
        completeBaseline();
        when(collector.collect()).thenReturn(List.of(notice("version one")));

        collectManuallyAndExpect(1, 0, 0);

        List<NotificationDelivery> deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().getChangeType()).isEqualTo(NoticeChangeType.NEW);
        assertThat(deliveries.getFirst().getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(subscriberDeliveries(deliveries.getFirst())).singleElement()
                .extracting(NotificationSubscriberDelivery::getStatus)
                .isEqualTo(NotificationDeliveryStatus.SENT);
        verify(emailSender).send(eq("workflow@example.com"), any());
    }

    @Test
    void repeatedManualCollectionDoesNotCreateOrSendDuplicate() throws Exception {
        completeBaseline();
        when(collector.collect()).thenReturn(List.of(notice("same content")));

        collectManuallyAndExpect(1, 0, 0);
        collectManuallyAndExpect(0, 0, 1);

        assertThat(deliveryRepository.findAll()).hasSize(1);
        assertThat(subscriberDeliveries(deliveryRepository.findAll().getFirst())).hasSize(1);
        verify(emailSender, times(1)).send(eq("workflow@example.com"), any());
    }

    @Test
    void scheduledCollectionAfterManualCollectionDoesNotSendDuplicate() throws Exception {
        completeBaseline();
        when(collector.collect()).thenReturn(List.of(notice("same content")));

        collectManuallyAndExpect(1, 0, 0);
        scheduler.runScheduledCollection();

        assertThat(deliveryRepository.findAll()).hasSize(1);
        assertThat(subscriberDeliveries(deliveryRepository.findAll().getFirst())).hasSize(1);
        verify(emailSender, times(1)).send(eq("workflow@example.com"), any());
    }

    @Test
    void firstManualCollectionEstablishesBaselineWithoutBulkNotification() throws Exception {
        when(collector.collect()).thenReturn(List.of(notice("historical content")));

        collectManuallyAndExpect(1, 0, 0);

        assertThat(baselineRepository.isCompleted(CHANNEL, TYPE, SOURCE_CODE)).isTrue();
        assertThat(deliveryRepository.findAll()).isEmpty();
        verify(emailSender, never()).send(any(), any());
    }

    @Test
    void changedFingerprintCreatesUpdatedNotification() throws Exception {
        completeBaseline();
        when(collector.collect())
                .thenReturn(List.of(notice("version one")))
                .thenReturn(List.of(notice("version two")));

        collectManuallyAndExpect(1, 0, 0);
        collectManuallyAndExpect(0, 1, 0);

        assertThat(deliveryRepository.findAll())
                .extracting(NotificationDelivery::getChangeType)
                .containsExactly(NoticeChangeType.NEW, NoticeChangeType.UPDATED);
        assertThat(deliveryRepository.findAll())
                .allMatch(delivery -> subscriberDeliveries(delivery).size() == 1);
        verify(emailSender, times(2)).send(eq("workflow@example.com"), any());
    }

    @Test
    void disabledMailStillCollectsAndLeavesDeliveryPending() throws Exception {
        completeBaseline();
        when(mailProperties.enabled()).thenReturn(false);
        when(collector.collect()).thenReturn(List.of(notice("mail disabled")));

        collectManuallyAndExpect(1, 0, 0);

        assertThat(noticeRepository.findByExternalId(EXTERNAL_ID)).isPresent();
        assertThat(deliveryRepository.findAll()).singleElement()
                .extracting(NotificationDelivery::getStatus)
                .isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(subscriberDeliveries(deliveryRepository.findAll().getFirst())).isEmpty();
        verify(emailSender, never()).send(any(), any());
    }

    private void collectManuallyAndExpect(int newCount, int updatedCount, int unchangedCount)
            throws Exception {
        mockMvc.perform(post("/api/external-notices/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newCount").value(newCount))
                .andExpect(jsonPath("$.updatedCount").value(updatedCount))
                .andExpect(jsonPath("$.unchangedCount").value(unchangedCount));
    }

    private void completeBaseline() {
        baselineRepository.completeIfAbsent(CHANNEL, TYPE, SOURCE_CODE, Instant.now());
    }

    private List<NotificationSubscriberDelivery> subscriberDeliveries(NotificationDelivery delivery) {
        return subscriberDeliveryRepository.findByNotificationDeliveryId(delivery.getId());
    }

    private CollectedNotice notice(String body) {
        return CollectedNotice.builder()
                .source(NoticeSource.PRIVACY_PORTAL)
                .externalId(EXTERNAL_ID)
                .sourceNoticeId("manual-workflow-test")
                .title("PIA important notice")
                .publishedDate(LocalDate.of(2026, 9, 4))
                .detailUrl("https://example.com/notices/manual-workflow-test")
                .body(body)
                .build();
    }
}
