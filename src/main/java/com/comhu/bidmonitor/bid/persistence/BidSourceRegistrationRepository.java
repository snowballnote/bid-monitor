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

    CheckStartOutcome tryStartCheck(
            long sourceId,
            String attemptId,
            Instant startedAt,
            Instant expiredBefore
    );

    boolean updateCheckResult(long sourceId, String expectedAttemptId, BidSourceCheckResult result);

    List<BidSourceRegistration> findAllLatestFirst();

    enum CheckStartOutcome {
        STARTED,
        RESTARTED_AFTER_TIMEOUT,
        REJECTED
    }
}
