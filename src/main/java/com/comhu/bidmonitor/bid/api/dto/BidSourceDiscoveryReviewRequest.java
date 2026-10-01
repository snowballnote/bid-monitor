package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.source.registration.BidSourceDiscoveryReviewService;

public record BidSourceDiscoveryReviewRequest(
        String reviewStatus,
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
) {
    public BidSourceDiscoveryReviewService.Update toUpdate() {
        return new BidSourceDiscoveryReviewService.Update(
                reviewStatus, listPageUrl, detailUrlPattern, identifierMapping, titleMapping,
                agencyMapping, publishedDateMapping, deadlineMapping, statusMapping,
                attachmentMapping, paginationMapping
        );
    }
}
