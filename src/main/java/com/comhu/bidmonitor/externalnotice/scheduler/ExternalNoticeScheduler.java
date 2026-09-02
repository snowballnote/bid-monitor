package com.comhu.bidmonitor.externalnotice.scheduler;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeAutomaticCollectionResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeAutomaticCollectionWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 설정된 주기에 맞춰 외부공지 수집 오케스트레이션을 호출한다.
 * 실제 수집·분류·저장 책임은 기존 ExternalNoticeCollectionService에 그대로 둔다.
 */
@Slf4j
public class ExternalNoticeScheduler {

    private final ExternalNoticeAutomaticCollectionWorkflow automaticCollectionWorkflow;
    private final boolean runOnStartup;
    private final AtomicBoolean collectionRunning = new AtomicBoolean(false);

    public ExternalNoticeScheduler(
            ExternalNoticeAutomaticCollectionWorkflow automaticCollectionWorkflow,
            @Value("${external-notice.scheduler.run-on-startup:false}") boolean runOnStartup
    ) {
        this.automaticCollectionWorkflow = automaticCollectionWorkflow;
        this.runOnStartup = runOnStartup;
    }

    @Scheduled(
            fixedDelayString = "${external-notice.scheduler.fixed-delay:3600000}",
            initialDelayString = "${external-notice.scheduler.initial-delay:3600000}"
    )
    public void runScheduledCollection() {
        runCollectionSafely("scheduled");
    }

    /** 시작 즉시 실행은 운영 환경에서 필요할 때만 별도 설정으로 활성화한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void runCollectionOnStartup() {
        if (runOnStartup) {
            runCollectionSafely("startup");
        }
    }

    private void runCollectionSafely(String trigger) {
        if (!collectionRunning.compareAndSet(false, true)) {
            log.warn("외부공지 자동 수집 건너뜀: 이전 실행이 아직 진행 중입니다. trigger={}", trigger);
            return;
        }

        try {
            log.info("외부공지 자동 수집 시작: trigger={}", trigger);
            ExternalNoticeAutomaticCollectionResult automaticResult = automaticCollectionWorkflow.run();
            var result = automaticResult.collectionResult();
            log.info(
                    "외부공지 자동 수집 완료: trigger={}, collectedCount={}, newCount={}, "
                            + "updatedCount={}, unchangedCount={}, piaRelatedCount={}, failedCount={}, "
                            + "notificationCandidateCount={}",
                    trigger,
                    result.getCollectedCount(),
                    result.getNewCount(),
                    result.getUpdatedCount(),
                    result.getUnchangedCount(),
                    result.getPiaRelatedCount(),
                    result.getFailedCount(),
                    automaticResult.notificationCandidates().size()
            );
        } catch (RuntimeException exception) {
            // 한 번의 전체 수집 실패가 Spring의 다음 예약 실행까지 중단시키지 않도록 여기서 경계를 만든다.
            log.error("외부공지 자동 수집 실패: trigger={}", trigger, exception);
        } finally {
            collectionRunning.set(false);
        }
    }
}
