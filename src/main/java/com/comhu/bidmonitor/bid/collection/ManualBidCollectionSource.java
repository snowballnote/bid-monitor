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

    default boolean usesGenericSchedule() {
        return false;
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

    class MeasuredCollectionException extends RuntimeException {
        private final int apiCallCount;
        private final String errorCode;

        public MeasuredCollectionException(int apiCallCount, Throwable cause) {
            this(apiCallCount, null, cause);
        }

        public MeasuredCollectionException(int apiCallCount, String errorCode, Throwable cause) {
            super("Measured collection failed", cause);
            this.apiCallCount = apiCallCount;
            this.errorCode = errorCode;
        }

        public int getApiCallCount() {
            return apiCallCount;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
