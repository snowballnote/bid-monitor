package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;

import java.time.Instant;

public record BidSourceRegistrationResponse(
        long sourceId,
        String sourceName,
        String siteUrl,
        String sourceCode,
        BidSourceRegistration.RegistrationStatus registrationStatus,
        BidSourceRegistration.CollectionMethod collectionMethod,
        boolean executionEnabled,
        BidSourceRegistration.CheckStatus checkStatus,
        BidSourceRegistration.CollectionMethod detectedCollectionMethod,
        Integer httpStatus,
        String contentType,
        Instant checkedAt,
        BidSourceRegistration.SafeFailureCode safeFailureCode,
        Instant createdAt,
        Instant updatedAt
) {

    public static BidSourceRegistrationResponse from(BidSourceRegistration registration) {
        return new BidSourceRegistrationResponse(
                registration.getSourceId(),
                registration.getSourceName(),
                registration.getSiteUrl(),
                registration.getSourceCode(),
                registration.getRegistrationStatus(),
                registration.getCollectionMethod(),
                registration.isExecutionEnabled(),
                registration.getCheckStatus(),
                registration.getDetectedCollectionMethod(),
                registration.getHttpStatus(),
                registration.getContentType(),
                registration.getCheckedAt(),
                registration.getSafeFailureCode(),
                registration.getCreatedAt(),
                registration.getUpdatedAt()
        );
    }
}
