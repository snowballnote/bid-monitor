package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeAutomaticCollectionResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeAutomaticCollectionWorkflow;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalNoticeSchedulerTests {

    @Test
    void delegatesScheduledCollectionToOrchestrationService() {
        ExternalNoticeAutomaticCollectionWorkflow workflow = mock(ExternalNoticeAutomaticCollectionWorkflow.class);
        when(workflow.run()).thenReturn(emptyAutomaticResult());
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(workflow, false);

        scheduler.runScheduledCollection();

        verify(workflow).run();
    }

    @Test
    void swallowsCollectionFailureSoLaterSchedulesCanRun() {
        ExternalNoticeAutomaticCollectionWorkflow workflow = mock(ExternalNoticeAutomaticCollectionWorkflow.class);
        when(workflow.run())
                .thenThrow(new IllegalStateException("collector unavailable"))
                .thenReturn(emptyAutomaticResult());
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(workflow, false);

        assertDoesNotThrow(scheduler::runScheduledCollection);
        assertDoesNotThrow(scheduler::runScheduledCollection);

        verify(workflow, times(2)).run();
    }

    @Test
    void skipsOverlappingExecution() throws Exception {
        ExternalNoticeAutomaticCollectionWorkflow workflow = mock(ExternalNoticeAutomaticCollectionWorkflow.class);
        CountDownLatch firstExecutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstExecution = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstExecutionStarted.countDown();
            assertTrue(releaseFirstExecution.await(2, TimeUnit.SECONDS));
            return emptyAutomaticResult();
        }).when(workflow).run();
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(workflow, false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstExecution = executor.submit(scheduler::runScheduledCollection);
            assertTrue(firstExecutionStarted.await(1, TimeUnit.SECONDS));

            scheduler.runScheduledCollection();
            releaseFirstExecution.countDown();
            firstExecution.get(2, TimeUnit.SECONDS);

            verify(workflow).run();
        } finally {
            releaseFirstExecution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void runsOnApplicationReadyOnlyWhenConfigured() {
        ExternalNoticeAutomaticCollectionWorkflow disabledWorkflow =
                mock(ExternalNoticeAutomaticCollectionWorkflow.class);
        ExternalNoticeScheduler disabledScheduler = new ExternalNoticeScheduler(disabledWorkflow, false);
        disabledScheduler.runCollectionOnStartup();
        verify(disabledWorkflow, times(0)).run();

        ExternalNoticeAutomaticCollectionWorkflow enabledWorkflow =
                mock(ExternalNoticeAutomaticCollectionWorkflow.class);
        when(enabledWorkflow.run()).thenReturn(emptyAutomaticResult());
        ExternalNoticeScheduler enabledScheduler = new ExternalNoticeScheduler(enabledWorkflow, true);
        enabledScheduler.runCollectionOnStartup();
        verify(enabledWorkflow).run();
    }

    private ExternalNoticeCollectionResult emptyResult() {
        return ExternalNoticeCollectionResult.builder().build();
    }

    private ExternalNoticeAutomaticCollectionResult emptyAutomaticResult() {
        return new ExternalNoticeAutomaticCollectionResult(emptyResult(), java.util.List.of());
    }
}
