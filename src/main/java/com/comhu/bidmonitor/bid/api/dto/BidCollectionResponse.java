package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionResult;

import java.time.LocalDate;
import java.util.List;

public record BidCollectionResponse(
        String status,
        LocalDate startDate,
        LocalDate endDate,
        List<BidCollectionSourceResponse> sources,
        List<String> skippedSourceCodes
) {
    public static BidCollectionResponse from(ManualBidCollectionResult result) {
        List<BidCollectionSourceResponse> sources = result.sourceResults().stream()
                .map(source -> new BidCollectionSourceResponse(
                        source.sourceCode(),
                        result.runIdsBySource().get(source.sourceCode()),
                        source.status().name(),
                        source.collectedCount(),
                        source.newCount(),
                        source.changedCount(),
                        source.unchangedCount(),
                        source.status() == com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult.Status.FAILED
                                ? 1 : 0,
                        source.errorCode()
                ))
                .toList();
        return new BidCollectionResponse(
                result.status().name(), result.queryStartDate(), result.queryEndDate(),
                sources, result.skippedSourceCodes()
        );
    }
}
