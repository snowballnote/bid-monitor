package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;

@Value
@Builder(toBuilder = true)
public class BidSourceState {

    String sourceCode;
    Instant lastAttemptAt;
    Instant lastSuccessAt;
    Instant lastFailureAt;
    int consecutiveFailures;
    Instant nextRunAt;
    Integer dailyLimit;
    int usedCalls;
    LocalDate quotaDate;
    Instant cooldownUntil;
    long cooldownSeconds;
    String lastErrorCode;
}
