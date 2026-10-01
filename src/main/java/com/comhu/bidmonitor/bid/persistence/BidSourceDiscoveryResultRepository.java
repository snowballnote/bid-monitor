package com.comhu.bidmonitor.bid.persistence;

import java.util.Optional;

public interface BidSourceDiscoveryResultRepository {

    BidSourceDiscoveryResult save(BidSourceDiscoveryResult result);

    Optional<BidSourceDiscoveryResult> findBySourceId(long sourceId);
}
