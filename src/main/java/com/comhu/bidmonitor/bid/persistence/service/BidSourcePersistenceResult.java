package com.comhu.bidmonitor.bid.persistence.service;

public record BidSourcePersistenceResult(
        String sourceCode,
        Status status,
        int collectedCount,
        int newCount,
        int changedCount,
        int unchangedCount,
        String errorCode
) {

    public enum Status {
        SUCCESS,
        FAILED
    }
}
