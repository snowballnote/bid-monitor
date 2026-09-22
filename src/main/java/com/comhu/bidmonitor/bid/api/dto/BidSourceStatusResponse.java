package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceState;

import java.time.Instant;
import java.time.LocalDate;

public record BidSourceStatusResponse(
        String sourceCode,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailures,
        Instant nextRunAt,
        Integer dailyLimit,
        int usedCalls,
        LocalDate quotaDate,
        Boolean executionEnabled
) {
    public static BidSourceStatusResponse from(BidSourceState state, Boolean executionEnabled) {
        return new BidSourceStatusResponse(
                state.getSourceCode(), state.getLastAttemptAt(), state.getLastSuccessAt(),
                state.getLastFailureAt(), state.getConsecutiveFailures(), state.getNextRunAt(),
                state.getDailyLimit(), state.getUsedCalls(), state.getQuotaDate(), executionEnabled
        );
    }
}
