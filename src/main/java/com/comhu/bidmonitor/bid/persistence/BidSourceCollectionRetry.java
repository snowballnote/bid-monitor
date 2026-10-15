package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;

public record BidSourceCollectionRetry(
        long id,
        String sourceCode,
        String scope,
        String operation,
        LocalDate windowStart,
        LocalDate windowEnd,
        int pageNumber,
        String sourceNoticeId,
        String revision,
        String payload,
        int retryCount,
        String lastErrorCode,
        Instant lastAttemptAt,
        Instant createdAt,
        Instant updatedAt
) {
}
