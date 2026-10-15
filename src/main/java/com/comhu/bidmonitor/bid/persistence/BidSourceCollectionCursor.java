package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;

public record BidSourceCollectionCursor(
        long id,
        String sourceCode,
        String scope,
        String operation,
        LocalDate windowStart,
        LocalDate windowEnd,
        int nextPage,
        int pageSize,
        Integer totalCountSnapshot,
        Status status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        PENDING,
        PARTIAL,
        COMPLETE
    }
}
