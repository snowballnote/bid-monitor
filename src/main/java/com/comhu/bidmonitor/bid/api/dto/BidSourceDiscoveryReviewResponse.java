package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;

import java.time.Instant;

public record BidSourceDiscoveryReviewResponse(
        long sourceId,
        String discoveryStatus,
        String reviewStatus,
        MappingValues detected,
        MappingValues confirmed,
        Instant analyzedAt,
        Instant updatedAt
) {
    public static BidSourceDiscoveryReviewResponse from(
            BidSourceDiscoveryResult discovery,
            BidSourceDiscoveryReview review
    ) {
        return new BidSourceDiscoveryReviewResponse(
                review.getSourceId(), discovery.getDiscoveryStatus().name(), review.getReviewStatus().name(),
                new MappingValues(
                        discovery.getListPageUrl(), discovery.getDetailUrlPattern(),
                        discovery.getIdentifierMapping(), discovery.getTitleMapping(), discovery.getAgencyMapping(),
                        discovery.getPublishedDateMapping(), discovery.getDeadlineMapping(), discovery.getStatusMapping(),
                        discovery.getAttachmentMapping(), discovery.getPaginationMapping()
                ),
                new MappingValues(
                        review.getListPageUrl(), review.getDetailUrlPattern(), review.getIdentifierMapping(),
                        review.getTitleMapping(), review.getAgencyMapping(), review.getPublishedDateMapping(),
                        review.getDeadlineMapping(), review.getStatusMapping(), review.getAttachmentMapping(),
                        review.getPaginationMapping()
                ),
                discovery.getAnalyzedAt(), review.getUpdatedAt()
        );
    }

    public record MappingValues(
            String listPageUrl,
            String detailUrlPattern,
            String identifierMapping,
            String titleMapping,
            String agencyMapping,
            String publishedDateMapping,
            String deadlineMapping,
            String statusMapping,
            String attachmentMapping,
            String paginationMapping
    ) { }
}
