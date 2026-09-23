package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;

import java.time.Instant;

public record BidSourceRegistrationResponse(
        long sourceId,
        String sourceName,
        String siteUrl,
        BidSourceRegistration.RegistrationStatus registrationStatus,
        BidSourceRegistration.CollectionMethod collectionMethod,
        boolean executionEnabled,
        Instant createdAt,
        Instant updatedAt
) {

    public static BidSourceRegistrationResponse from(BidSourceRegistration registration) {
        return new BidSourceRegistrationResponse(
                registration.getSourceId(),
                registration.getSourceName(),
                registration.getSiteUrl(),
                registration.getRegistrationStatus(),
                registration.getCollectionMethod(),
                registration.isExecutionEnabled(),
                registration.getCreatedAt(),
                registration.getUpdatedAt()
        );
    }
}
