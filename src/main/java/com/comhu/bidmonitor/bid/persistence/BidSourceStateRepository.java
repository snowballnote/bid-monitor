package com.comhu.bidmonitor.bid.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BidSourceStateRepository {

    BidSourceState save(BidSourceState state);

    Optional<BidSourceState> findBySourceCode(String sourceCode);

    List<BidSourceState> findAll();

    /** Atomically reserves one call from the source's quota for the supplied quota date. */
    boolean tryReserveDailyCall(String sourceCode, LocalDate quotaDate, int defaultDailyLimit);
}
