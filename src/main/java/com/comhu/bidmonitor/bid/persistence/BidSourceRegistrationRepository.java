package com.comhu.bidmonitor.bid.persistence;

import java.util.List;
import java.util.Optional;

public interface BidSourceRegistrationRepository {

    BidSourceRegistration save(BidSourceRegistration registration);

    Optional<BidSourceRegistration> findById(long sourceId);

    List<BidSourceRegistration> findAllLatestFirst();
}
