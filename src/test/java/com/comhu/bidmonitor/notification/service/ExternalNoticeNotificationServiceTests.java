package com.comhu.bidmonitor.notification.service;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.persistence.NotificationBaselineRepository;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalNoticeNotificationServiceTests {

    private static final String SOURCE_CODE = "PRIVACY_PORTAL";
    private static final Instant DETECTED_AT = Instant.parse("2026-09-02T01:00:00Z");
    private static final String FINGERPRINT_ONE = "1".repeat(64);
    private static final String FINGERPRINT_TWO = "2".repeat(64);

    private final Map<Long, ExternalNotice> notices = new HashMap<>();
    private final Set<String> baselineKeys = new HashSet<>();
    private final Map<String, NotificationDelivery> deliveries = new HashMap<>();
    private final AtomicLong deliveryIds = new AtomicLong();

    private ExternalNoticeNotificationService service;

    @BeforeEach
    void setUp() {
        ExternalNoticeRepository noticeRepository = mock(ExternalNoticeRepository.class);
        when(noticeRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(notices.get(invocation.getArgument(0))));

        NotificationBaselineRepository baselineRepository = mock(NotificationBaselineRepository.class);
        when(baselineRepository.completeIfAbsent(any(), any(), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0) + ":" + invocation.getArgument(1)
                    + ":" + invocation.getArgument(2);
            return baselineKeys.add(key);
        });

        NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);
        when(deliveryRepository.createPendingIfAbsent(any())).thenAnswer(invocation -> {
            NotificationDelivery requested = invocation.getArgument(0);
            String key = deliveryKey(requested);
            if (deliveries.containsKey(key)) {
                return Optional.empty();
            }
            NotificationDelivery saved = NotificationDelivery.builder()
                    .id(deliveryIds.incrementAndGet())
                    .channel(requested.getChannel())
                    .notificationType(requested.getNotificationType())
                    .sourceCode(requested.getSourceCode())
                    .externalId(requested.getExternalId())
                    .changeType(requested.getChangeType())
                    .contentFingerprint(requested.getContentFingerprint())
                    .status(requested.getStatus())
                    .createdAt(requested.getCreatedAt())
                    .build();
            deliveries.put(key, saved);
            return Optional.of(saved);
        });

        service = new ExternalNoticeNotificationService(
                noticeRepository,
                baselineRepository,
                deliveryRepository,
                Clock.fixed(DETECTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void piaNewCreatesCandidateAfterBaseline() {
        completeBaseline();
        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_ONE));

        List<ExternalNoticeNotificationCandidate> candidates = service.createCandidates(
                result(change(1L, "PIA-1", NoticeChangeType.NEW, FINGERPRINT_ONE))
        );

        assertEquals(1, candidates.size());
        assertEquals(NoticeChangeType.NEW, candidates.getFirst().getChangeType());
        assertEquals(List.of("개인정보 영향평가"), candidates.getFirst().getMatchedKeywords());
        assertEquals(DETECTED_AT, candidates.getFirst().getDetectedAt());
    }

    @Test
    void piaUpdatedCreatesCandidateAfterBaseline() {
        completeBaseline();
        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_TWO));

        List<ExternalNoticeNotificationCandidate> candidates = service.createCandidates(
                result(change(1L, "PIA-1", NoticeChangeType.UPDATED, FINGERPRINT_TWO))
        );

        assertEquals(1, candidates.size());
        assertEquals(NoticeChangeType.UPDATED, candidates.getFirst().getChangeType());
    }

    @Test
    void piaUnchangedDoesNotCreateCandidate() {
        completeBaseline();
        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_ONE));

        assertTrue(service.createCandidates(
                result(change(1L, "PIA-1", NoticeChangeType.UNCHANGED, FINGERPRINT_ONE))
        ).isEmpty());
    }

    @Test
    void nonPiaNewDoesNotCreateCandidate() {
        completeBaseline();
        notices.put(1L, notice(1L, "NORMAL-1", false, FINGERPRINT_ONE));

        assertTrue(service.createCandidates(
                result(change(1L, "NORMAL-1", NoticeChangeType.NEW, FINGERPRINT_ONE))
        ).isEmpty());
    }

    @Test
    void sameExternalIdAndFingerprintDoesNotCreateDuplicateCandidate() {
        completeBaseline();
        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_ONE));
        ExternalNoticeCollectionResult result =
                result(change(1L, "PIA-1", NoticeChangeType.NEW, FINGERPRINT_ONE));

        assertEquals(1, service.createCandidates(result).size());
        assertTrue(service.createCandidates(result).isEmpty());
        assertEquals(1, deliveries.size());
    }

    @Test
    void sameExternalIdWithNewFingerprintCreatesUpdatedCandidate() {
        completeBaseline();
        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_ONE));
        assertEquals(1, service.createCandidates(
                result(change(1L, "PIA-1", NoticeChangeType.NEW, FINGERPRINT_ONE))
        ).size());

        notices.put(1L, notice(1L, "PIA-1", true, FINGERPRINT_TWO));
        List<ExternalNoticeNotificationCandidate> updatedCandidates = service.createCandidates(
                result(change(1L, "PIA-1", NoticeChangeType.UPDATED, FINGERPRINT_TWO))
        );

        assertEquals(1, updatedCandidates.size());
        assertEquals(NoticeChangeType.UPDATED, updatedCandidates.getFirst().getChangeType());
        assertEquals(2, deliveries.size());
    }

    @Test
    void firstAutomaticRunBecomesBaselineWithoutCandidates() {
        notices.put(1L, notice(1L, "HISTORICAL-PIA", true, FINGERPRINT_ONE));

        assertTrue(service.createCandidates(
                result(change(1L, "HISTORICAL-PIA", NoticeChangeType.NEW, FINGERPRINT_ONE))
        ).isEmpty());
        assertEquals(1, baselineKeys.size());
        assertTrue(deliveries.isEmpty());
    }

    @Test
    void newNoticeAfterBaselineCreatesCandidate() {
        notices.put(1L, notice(1L, "HISTORICAL-PIA", true, FINGERPRINT_ONE));
        service.createCandidates(
                result(change(1L, "HISTORICAL-PIA", NoticeChangeType.NEW, FINGERPRINT_ONE))
        );

        notices.put(2L, notice(2L, "NEW-PIA", true, FINGERPRINT_TWO));
        List<ExternalNoticeNotificationCandidate> candidates = service.createCandidates(
                result(change(2L, "NEW-PIA", NoticeChangeType.NEW, FINGERPRINT_TWO))
        );

        assertEquals(1, candidates.size());
        assertEquals("NEW-PIA", candidates.getFirst().getExternalId());
    }

    private void completeBaseline() {
        baselineKeys.add("EMAIL:PIA_EXTERNAL_NOTICE:" + SOURCE_CODE);
    }

    private ExternalNotice notice(Long id, String externalId, boolean piaRelated, String fingerprint) {
        return ExternalNotice.builder()
                .id(id)
                .sourceCode(SOURCE_CODE)
                .externalId(externalId)
                .sourceNoticeId(externalId)
                .title("PIA 중요공지 " + externalId)
                .publishedDate(LocalDate.of(2026, 9, 2))
                .detailUrl("https://example.com/notices/" + externalId)
                .piaRelated(piaRelated)
                .matchedKeyword("개인정보 영향평가")
                .fingerprint(fingerprint)
                .firstSeenAt(DETECTED_AT)
                .lastSeenAt(DETECTED_AT)
                .build();
    }

    private NoticeChangeResult change(
            Long noticeId,
            String externalId,
            NoticeChangeType changeType,
            String fingerprint
    ) {
        return NoticeChangeResult.builder()
                .noticeId(noticeId)
                .externalId(externalId)
                .changeType(changeType)
                .currentFingerprint(fingerprint)
                .build();
    }

    private ExternalNoticeCollectionResult result(NoticeChangeResult changeResult) {
        return ExternalNoticeCollectionResult.builder().noticeResult(changeResult).build();
    }

    private String deliveryKey(NotificationDelivery delivery) {
        return delivery.getChannel() + ":" + delivery.getNotificationType() + ":"
                + delivery.getSourceCode() + ":" + delivery.getExternalId() + ":"
                + delivery.getContentFingerprint();
    }
}
