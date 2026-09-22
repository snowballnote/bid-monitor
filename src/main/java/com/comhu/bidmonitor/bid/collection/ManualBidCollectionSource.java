package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** One independently executable bid source used by the manual coordinator. */
public interface ManualBidCollectionSource {

    String sourceCode();

    default boolean executionEnabled() {
        return true;
    }

    CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> allowedLicenseCodes);

    record CollectionBatch(List<BidQualificationDto> candidates, Integer apiCallCount) {
        public CollectionBatch {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            if (apiCallCount != null && apiCallCount < 0) {
                throw new IllegalArgumentException("apiCallCount must not be negative.");
            }
        }

        public static CollectionBatch unmeasured(List<BidQualificationDto> candidates) {
            return new CollectionBatch(candidates, null);
        }
    }
}
