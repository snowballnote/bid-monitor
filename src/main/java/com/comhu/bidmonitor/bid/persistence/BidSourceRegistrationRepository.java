package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BidSourceRegistrationRepository {

    BidSourceRegistration save(BidSourceRegistration registration);

    Optional<BidSourceRegistration> findById(long sourceId);

    boolean updateReview(
            long sourceId,
            BidSourceRegistration.RegistrationStatus expectedStatus,
            BidSourceRegistration.RegistrationStatus registrationStatus,
            BidSourceRegistration.CollectionMethod collectionMethod,
            Instant updatedAt
    );

    boolean markCheckStarted(long sourceId);

    boolean updateCheckResult(long sourceId, BidSourceCheckResult result);

    List<BidSourceRegistration> findAllLatestFirst();
}
