package com.comhu.bidmonitor.bid.source.registration;

import java.time.Instant;

public record BidSourceCatalogItem(
        Long sourceId,
        String sourceCode,
        String sourceName,
        String siteUrl,
        SourceType sourceType,
        String collectionMethod,
        boolean executionEnabled,
        String registrationStatus,
        String checkStatus,
        String discoveryStatus,
        String reviewStatus,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String safeFailureCode
) {

    public enum SourceType {
        FIXED,
        DISCOVERED
    }
}
