package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface BidCollectionLockRepository {

    Optional<BidCollectionLock> findForUpdate(String sourceCode, LocalDate startDate, LocalDate endDate);

    void insert(BidCollectionLock lock);

    void replace(BidCollectionLock lock);

    void attachRun(String sourceCode, LocalDate startDate, LocalDate endDate, String ownerToken, long runId);

    boolean renew(String sourceCode, LocalDate startDate, LocalDate endDate,
                  String ownerToken, Instant leaseExpiresAt);

    boolean release(String sourceCode, LocalDate startDate, LocalDate endDate, String ownerToken);
}
