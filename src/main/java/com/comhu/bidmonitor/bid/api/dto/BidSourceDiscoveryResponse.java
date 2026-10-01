package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;

import java.time.Instant;
import java.util.List;

public record BidSourceDiscoveryResponse(
        long sourceId,
        String discoveryStatus,
        String detectedCollectionMethod,
        String listPageUrl,
        String detailUrlPattern,
        String identifierConfidence,
        String titleConfidence,
        String deadlineConfidence,
        boolean attachmentDetected,
        boolean paginationDetected,
        List<String> reasonCodes,
        Instant analyzedAt
) {
    public static BidSourceDiscoveryResponse from(BidSourceDiscoveryResult result) {
        return new BidSourceDiscoveryResponse(
                result.getSourceId(), result.getDiscoveryStatus().name(),
                result.getDetectedCollectionMethod().name(), result.getListPageUrl(),
                result.getDetailUrlPattern(), result.getIdentifierConfidence().name(),
                result.getTitleConfidence().name(), result.getDeadlineConfidence().name(),
                result.isAttachmentDetected(), result.isPaginationDetected(),
                result.getReasonCodes(), result.getAnalyzedAt()
        );
    }
}
