package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;

public record BidSourceCheckResult(
        BidSourceRegistration.CheckStatus checkStatus,
        BidSourceRegistration.CollectionMethod detectedCollectionMethod,
        Integer httpStatus,
        String contentType,
        Instant checkedAt,
        BidSourceRegistration.SafeFailureCode safeFailureCode
) {
}
