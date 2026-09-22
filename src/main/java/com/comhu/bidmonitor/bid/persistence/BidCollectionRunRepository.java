package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BidCollectionRunRepository {

    BidCollectionRun save(BidCollectionRun run);

    BidCollectionRun update(BidCollectionRun run);

    Optional<BidCollectionRun> findById(Long id);

    List<BidCollectionRun> findBySourceCodeLatestFirst(String sourceCode);

    boolean failIfRunning(long id, Instant finishedAt, String errorCode);
}
