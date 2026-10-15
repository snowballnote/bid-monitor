package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface BidSourceCollectionRetryRepository {

    BidSourceCollectionRetry savePending(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageNumber,
            String sourceNoticeId,
            String revision,
            String payload,
            Instant now
    );

    List<BidSourceCollectionRetry> findPending(String sourceCode, String scope);

    void recordFailure(long id, String errorCode, Instant attemptedAt);

    void delete(long id);
}
