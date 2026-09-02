package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;

import java.util.List;

/** 자동 실행에서 수집 결과와 이번 실행의 신규 알림 후보를 함께 반환한다. */
public record ExternalNoticeAutomaticCollectionResult(
        ExternalNoticeCollectionResult collectionResult,
        List<ExternalNoticeNotificationCandidate> notificationCandidates
) {
}
