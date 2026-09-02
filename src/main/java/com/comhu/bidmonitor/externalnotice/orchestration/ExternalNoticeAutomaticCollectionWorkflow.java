package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.service.ExternalNoticeNotificationService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Scheduler와 수동 API 사이에 자동 실행 전용 수집·후처리 순서를 정의한다. */
@Service
public class ExternalNoticeAutomaticCollectionWorkflow {

    private final ExternalNoticeCollectionService collectionService;
    private final ExternalNoticeNotificationService notificationService;

    public ExternalNoticeAutomaticCollectionWorkflow(
            ExternalNoticeCollectionService collectionService,
            ExternalNoticeNotificationService notificationService
    ) {
        this.collectionService = collectionService;
        this.notificationService = notificationService;
    }

    public ExternalNoticeAutomaticCollectionResult run() {
        ExternalNoticeCollectionResult collectionResult = collectionService.runCollection();
        List<ExternalNoticeNotificationCandidate> candidates =
                notificationService.createCandidates(collectionResult);
        return new ExternalNoticeAutomaticCollectionResult(collectionResult, candidates);
    }
}
