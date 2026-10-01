package com.comhu.bidmonitor.bid.persistence;

import java.util.Optional;

public interface BidSourceDiscoveryReviewRepository {

    BidSourceDiscoveryReview save(BidSourceDiscoveryReview review);

    Optional<BidSourceDiscoveryReview> findBySourceId(long sourceId);
}
