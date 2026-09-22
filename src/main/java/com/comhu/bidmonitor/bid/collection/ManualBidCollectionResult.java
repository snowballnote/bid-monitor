package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ManualBidCollectionResult(
        Status status,
        LocalDate queryStartDate,
        LocalDate queryEndDate,
        List<BidSourcePersistenceResult> sourceResults,
        List<String> skippedSourceCodes,
        Map<String, Long> runIdsBySource
) {
    public ManualBidCollectionResult {
        sourceResults = List.copyOf(sourceResults);
        skippedSourceCodes = List.copyOf(skippedSourceCodes);
        runIdsBySource = Map.copyOf(runIdsBySource);
    }

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }
}
