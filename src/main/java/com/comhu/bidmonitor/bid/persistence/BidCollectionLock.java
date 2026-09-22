package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;

public record BidCollectionLock(
        String sourceCode,
        LocalDate queryStartDate,
        LocalDate queryEndDate,
        String ownerToken,
        Instant acquiredAt,
        Instant leaseExpiresAt,
        Long runId
) {
}
