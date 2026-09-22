package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult;

import java.util.List;

public record ManualBidCollectionResult(
        Status status,
        List<BidSourcePersistenceResult> sourceResults,
        List<String> skippedSourceCodes
) {
    public ManualBidCollectionResult {
        sourceResults = List.copyOf(sourceResults);
        skippedSourceCodes = List.copyOf(skippedSourceCodes);
    }

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }
}
