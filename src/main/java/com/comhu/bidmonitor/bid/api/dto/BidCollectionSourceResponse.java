package com.comhu.bidmonitor.bid.api.dto;

public record BidCollectionSourceResponse(
        String sourceCode,
        Long runId,
        String status,
        int collectedCount,
        int newCount,
        int updatedCount,
        int unchangedCount,
        int failedCount,
        String errorCode
) {
}
