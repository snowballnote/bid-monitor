package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;

public record BidSourceDiscoveryReviewAudit(
        Long auditId,
        long sourceId,
        Action action,
        BidSourceDiscoveryReview.ReviewStatus previousStatus,
        BidSourceDiscoveryReview.ReviewStatus newStatus,
        String actor,
        Instant createdAt
) {
    public enum Action {
        REVIEW_UPDATED,
        APPROVED,
        REJECTED
    }
}
