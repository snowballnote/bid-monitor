package com.comhu.bidmonitor.bid.persistence.service;

import java.util.List;

public record BidCollectionPersistenceResult(List<BidSourcePersistenceResult> sourceResults) {

    public BidCollectionPersistenceResult {
        sourceResults = List.copyOf(sourceResults);
    }

    public int successfulSourceCount() {
        return (int) sourceResults.stream()
                .filter(result -> result.status() == BidSourcePersistenceResult.Status.SUCCESS)
                .count();
    }

    public int failedSourceCount() {
        return sourceResults.size() - successfulSourceCount();
    }
}
