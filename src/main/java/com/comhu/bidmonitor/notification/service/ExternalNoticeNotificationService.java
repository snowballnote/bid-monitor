package com.comhu.bidmonitor.notification.service;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.model.NotificationChannel;
import com.comhu.bidmonitor.notification.model.NotificationDeliveryStatus;
import com.comhu.bidmonitor.notification.model.NotificationType;
import com.comhu.bidmonitor.notification.persistence.NotificationBaselineRepository;
import com.comhu.bidmonitor.notification.persistence.NotificationDelivery;
import com.comhu.bidmonitor.notification.persistence.NotificationDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 기존 변경 판정 결과에서 PIA 알림 후보만 선별하고 중복 없는 PENDING 이력을 예약한다. */
@Service
public class ExternalNoticeNotificationService {

    private static final NotificationChannel CHANNEL = NotificationChannel.EMAIL;
    private static final NotificationType NOTIFICATION_TYPE = NotificationType.PIA_EXTERNAL_NOTICE;

    private final ExternalNoticeRepository noticeRepository;
    private final NotificationBaselineRepository baselineRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final Clock clock;

    public ExternalNoticeNotificationService(
            ExternalNoticeRepository noticeRepository,
            NotificationBaselineRepository baselineRepository,
            NotificationDeliveryRepository deliveryRepository,
            Clock clock
    ) {
        this.noticeRepository = noticeRepository;
        this.baselineRepository = baselineRepository;
        this.deliveryRepository = deliveryRepository;
        this.clock = clock;
    }

    /** 자동·수동 공통 수집 결과를 처리하며, 출처별 첫 실행은 baseline으로만 기록한다. */
    @Transactional
    public List<ExternalNoticeNotificationCandidate> createCandidates(
            ExternalNoticeCollectionResult collectionResult
    ) {
        Objects.requireNonNull(collectionResult, "알림 후보를 만들 수집 결과는 null일 수 없습니다.");
        Instant detectedAt = clock.instant();
        Map<String, List<NoticeContext>> resultsBySource = loadResultsBySource(collectionResult);
        List<ExternalNoticeNotificationCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, List<NoticeContext>> sourceEntry : resultsBySource.entrySet()) {
            String sourceCode = sourceEntry.getKey();
            boolean firstAutomaticRun = baselineRepository.completeIfAbsent(
                    CHANNEL,
                    NOTIFICATION_TYPE,
                    sourceCode,
                    detectedAt
            );
            if (firstAutomaticRun) {
                continue;
            }

            for (NoticeContext context : sourceEntry.getValue()) {
                createCandidateIfEligible(context, detectedAt).ifPresent(candidates::add);
            }
        }
        return List.copyOf(candidates);
    }

    private Map<String, List<NoticeContext>> loadResultsBySource(
            ExternalNoticeCollectionResult collectionResult
    ) {
        Map<String, List<NoticeContext>> grouped = new LinkedHashMap<>();
        for (NoticeChangeResult changeResult : collectionResult.getNoticeResults()) {
            ExternalNotice notice = noticeRepository.findById(changeResult.getNoticeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "알림 후보의 저장된 외부공지를 찾을 수 없습니다: " + changeResult.getNoticeId()
                    ));
            grouped.computeIfAbsent(notice.getSourceCode(), ignored -> new ArrayList<>())
                    .add(new NoticeContext(changeResult, notice));
        }
        return grouped;
    }

    private Optional<ExternalNoticeNotificationCandidate> createCandidateIfEligible(
            NoticeContext context,
            Instant detectedAt
    ) {
        NoticeChangeType changeType = context.changeResult().getChangeType();
        if (!context.notice().isPiaRelated()
                || (changeType != NoticeChangeType.NEW && changeType != NoticeChangeType.UPDATED)) {
            return Optional.empty();
        }

        String fingerprint = Objects.requireNonNull(
                context.changeResult().getCurrentFingerprint(),
                "알림 후보의 현재 fingerprint는 null일 수 없습니다."
        );
        NotificationDelivery pendingDelivery = NotificationDelivery.builder()
                .channel(CHANNEL)
                .notificationType(NOTIFICATION_TYPE)
                .sourceCode(context.notice().getSourceCode())
                .externalId(context.notice().getExternalId())
                .changeType(changeType)
                .contentFingerprint(fingerprint)
                .status(NotificationDeliveryStatus.PENDING)
                .createdAt(detectedAt)
                .build();

        return deliveryRepository.createPendingIfAbsent(pendingDelivery)
                .map(savedDelivery -> toCandidate(context, savedDelivery, detectedAt));
    }

    private ExternalNoticeNotificationCandidate toCandidate(
            NoticeContext context,
            NotificationDelivery savedDelivery,
            Instant detectedAt
    ) {
        ExternalNotice notice = context.notice();
        return ExternalNoticeNotificationCandidate.builder()
                .deliveryId(savedDelivery.getId())
                .noticeId(notice.getId())
                .sourceCode(notice.getSourceCode())
                .externalId(notice.getExternalId())
                .title(notice.getTitle())
                .publishedDate(notice.getPublishedDate())
                .changeType(context.changeResult().getChangeType())
                .matchedKeywords(notice.getMatchedKeywords())
                .detailUrl(notice.getDetailUrl())
                .contentFingerprint(context.changeResult().getCurrentFingerprint())
                .detectedAt(detectedAt)
                .build();
    }

    private record NoticeContext(NoticeChangeResult changeResult, ExternalNotice notice) {
    }
}
