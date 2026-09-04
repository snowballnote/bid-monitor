package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;

import java.util.List;

/** 한 번의 공통 workflow에서 나온 수집 결과와 신규 알림 후보를 함께 반환한다. */
public record ExternalNoticeCollectionWorkflowResult(
        ExternalNoticeCollectionResult collectionResult,
        List<ExternalNoticeNotificationCandidate> notificationCandidates
) {
}
