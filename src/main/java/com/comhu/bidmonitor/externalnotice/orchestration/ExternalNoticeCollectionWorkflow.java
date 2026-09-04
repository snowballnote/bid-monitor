package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.dispatch.NotificationDispatcher;
import com.comhu.bidmonitor.notification.service.ExternalNoticeNotificationService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Scheduler와 수동 API가 공유하는 수집·알림 후보 생성·발송 순서를 정의한다. */
@Service
public class ExternalNoticeCollectionWorkflow {

    private final ExternalNoticeCollectionService collectionService;
    private final ExternalNoticeNotificationService notificationService;
    private final NotificationDispatcher notificationDispatcher;

    public ExternalNoticeCollectionWorkflow(
            ExternalNoticeCollectionService collectionService,
            ExternalNoticeNotificationService notificationService,
            NotificationDispatcher notificationDispatcher
    ) {
        this.collectionService = collectionService;
        this.notificationService = notificationService;
        this.notificationDispatcher = notificationDispatcher;
    }

    public ExternalNoticeCollectionWorkflowResult run() {
        ExternalNoticeCollectionResult collectionResult = collectionService.runCollection();
        List<ExternalNoticeNotificationCandidate> candidates =
                notificationService.createCandidates(collectionResult);
        notificationDispatcher.dispatchPending();
        return new ExternalNoticeCollectionWorkflowResult(collectionResult, candidates);
    }
}
