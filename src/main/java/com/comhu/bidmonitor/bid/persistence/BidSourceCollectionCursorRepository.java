package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface BidSourceCollectionCursorRepository {

    BidSourceCollectionCursor getOrCreate(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageSize,
            Instant now
    );

    List<BidSourceCollectionCursor> findIncomplete(String sourceCode, String scope);

    boolean reopenCompleted(long id, long expectedVersion, Instant updatedAt);

    boolean advance(
            long id,
            long expectedVersion,
            int nextPage,
            Integer totalCountSnapshot,
            BidSourceCollectionCursor.Status status,
            Instant updatedAt
    );
}
