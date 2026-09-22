package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;

@Value
@Builder(toBuilder = true)
public class BidCollectionRun {

    Long id;
    String sourceCode;
    TriggerType triggerType;
    LocalDate queryStartDate;
    LocalDate queryEndDate;
    Instant startedAt;
    Instant finishedAt;
    Status status;
    Integer apiCallCount;
    int collectedCount;
    int newCount;
    int changedCount;
    int failureCount;
    String errorCode;

    public enum TriggerType {
        AUTOMATIC,
        MANUAL
    }

    public enum Status {
        RUNNING,
        SUCCESS,
        PARTIAL,
        FAILED
    }
}
