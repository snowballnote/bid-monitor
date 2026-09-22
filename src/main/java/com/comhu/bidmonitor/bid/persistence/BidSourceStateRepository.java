package com.comhu.bidmonitor.bid.persistence;

import java.util.List;
import java.util.Optional;

public interface BidSourceStateRepository {

    BidSourceState save(BidSourceState state);

    Optional<BidSourceState> findBySourceCode(String sourceCode);

    List<BidSourceState> findAll();
}
