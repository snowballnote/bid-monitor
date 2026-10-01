package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class BidSourceDiscoveryReview {

    public static final String UNKNOWN = "UNKNOWN";

    long sourceId;
    ReviewStatus reviewStatus;
    String listPageUrl;
    String detailUrlPattern;
    String identifierMapping;
    String titleMapping;
    String agencyMapping;
    String publishedDateMapping;
    String deadlineMapping;
    String statusMapping;
    String attachmentMapping;
    String paginationMapping;
    Instant updatedAt;

    public enum ReviewStatus {
        PENDING_REVIEW,
        APPROVED,
        REJECTED
    }
}
