package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.notification.dispatch.NotificationDispatcher;
import com.comhu.bidmonitor.notification.model.ExternalNoticeNotificationCandidate;
import com.comhu.bidmonitor.notification.service.ExternalNoticeNotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalNoticeAutomaticCollectionWorkflowTests {

    @Test
    void connectsAutomaticCollectionResultToNotificationCandidateService() {
        ExternalNoticeCollectionService collectionService = mock(ExternalNoticeCollectionService.class);
        ExternalNoticeNotificationService notificationService = mock(ExternalNoticeNotificationService.class);
        NotificationDispatcher notificationDispatcher = mock(NotificationDispatcher.class);
        ExternalNoticeCollectionResult collectionResult = ExternalNoticeCollectionResult.builder().build();
        List<ExternalNoticeNotificationCandidate> candidates = List.of(
                ExternalNoticeNotificationCandidate.builder().externalId("PIA-1").build()
        );
        when(collectionService.runCollection()).thenReturn(collectionResult);
        when(notificationService.createCandidates(collectionResult)).thenReturn(candidates);
        ExternalNoticeAutomaticCollectionWorkflow workflow =
                new ExternalNoticeAutomaticCollectionWorkflow(
                        collectionService,
                        notificationService,
                        notificationDispatcher
                );

        ExternalNoticeAutomaticCollectionResult result = workflow.run();

        assertSame(collectionResult, result.collectionResult());
        assertSame(candidates, result.notificationCandidates());
        verify(collectionService).runCollection();
        verify(notificationService).createCandidates(collectionResult);
        verify(notificationDispatcher).dispatchPending();
    }
}
