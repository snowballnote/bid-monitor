package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;

import java.time.Instant;
import java.time.LocalDate;

public record BidCollectionRunResponse(
        long runId,
        String sourceCode,
        LocalDate startDate,
        LocalDate endDate,
        String triggerType,
        String status,
        Instant startedAt,
        Instant completedAt,
        int collectedCount,
        int newCount,
        int updatedCount,
        int failedCount,
        Integer apiCallCount,
        String errorCode
) {
    public static BidCollectionRunResponse from(BidCollectionRun run) {
        return new BidCollectionRunResponse(
                run.getId(), run.getSourceCode(), run.getQueryStartDate(), run.getQueryEndDate(),
                run.getTriggerType().name(), run.getStatus().name(), run.getStartedAt(), run.getFinishedAt(),
                run.getCollectedCount(), run.getNewCount(), run.getChangedCount(), run.getFailureCount(),
                run.getApiCallCount(), run.getErrorCode()
        );
    }
}
