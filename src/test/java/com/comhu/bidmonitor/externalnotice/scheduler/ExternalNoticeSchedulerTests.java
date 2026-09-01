package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionService;
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
        ExternalNoticeCollectionService collectionService = mock(ExternalNoticeCollectionService.class);
        when(collectionService.runCollection()).thenReturn(emptyResult());
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(collectionService, false);

        scheduler.runScheduledCollection();

        verify(collectionService).runCollection();
    }

    @Test
    void swallowsCollectionFailureSoLaterSchedulesCanRun() {
        ExternalNoticeCollectionService collectionService = mock(ExternalNoticeCollectionService.class);
        when(collectionService.runCollection())
                .thenThrow(new IllegalStateException("collector unavailable"))
                .thenReturn(emptyResult());
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(collectionService, false);

        assertDoesNotThrow(scheduler::runScheduledCollection);
        assertDoesNotThrow(scheduler::runScheduledCollection);

        verify(collectionService, times(2)).runCollection();
    }

    @Test
    void skipsOverlappingExecution() throws Exception {
        ExternalNoticeCollectionService collectionService = mock(ExternalNoticeCollectionService.class);
        CountDownLatch firstExecutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstExecution = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstExecutionStarted.countDown();
            assertTrue(releaseFirstExecution.await(2, TimeUnit.SECONDS));
            return emptyResult();
        }).when(collectionService).runCollection();
        ExternalNoticeScheduler scheduler = new ExternalNoticeScheduler(collectionService, false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstExecution = executor.submit(scheduler::runScheduledCollection);
            assertTrue(firstExecutionStarted.await(1, TimeUnit.SECONDS));

            scheduler.runScheduledCollection();
            releaseFirstExecution.countDown();
            firstExecution.get(2, TimeUnit.SECONDS);

            verify(collectionService).runCollection();
        } finally {
            releaseFirstExecution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void runsOnApplicationReadyOnlyWhenConfigured() {
        ExternalNoticeCollectionService disabledService = mock(ExternalNoticeCollectionService.class);
        ExternalNoticeScheduler disabledScheduler = new ExternalNoticeScheduler(disabledService, false);
        disabledScheduler.runCollectionOnStartup();
        verify(disabledService, times(0)).runCollection();

        ExternalNoticeCollectionService enabledService = mock(ExternalNoticeCollectionService.class);
        when(enabledService.runCollection()).thenReturn(emptyResult());
        ExternalNoticeScheduler enabledScheduler = new ExternalNoticeScheduler(enabledService, true);
        enabledScheduler.runCollectionOnStartup();
        verify(enabledService).runCollection();
    }

    private ExternalNoticeCollectionResult emptyResult() {
        return ExternalNoticeCollectionResult.builder().build();
    }
}
