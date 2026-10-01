package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class BidSourceDiscoveryResult {

    long sourceId;
    DiscoveryStatus discoveryStatus;
    BidSourceRegistration.CollectionMethod detectedCollectionMethod;
    String listPageUrl;
    String detailUrlPattern;
    Confidence identifierConfidence;
    Confidence titleConfidence;
    Confidence deadlineConfidence;
    boolean attachmentDetected;
    boolean paginationDetected;
    @Builder.Default
    List<String> reasonCodes = List.of();
    Instant analyzedAt;

    public enum DiscoveryStatus {
        NOT_ANALYZED,
        ANALYZING,
        READY,
        MANUAL_REVIEW,
        UNSUPPORTED,
        FAILED
    }

    public enum Confidence {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }
}
