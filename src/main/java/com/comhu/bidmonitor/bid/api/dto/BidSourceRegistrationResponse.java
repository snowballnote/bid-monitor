package com.comhu.bidmonitor.bid.api.dto;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.source.registration.BidSourceSiteIdentificationService;

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
        Instant updatedAt,
        BidSourceSiteIdentificationService.Status siteIdentification,
        String matchedSourceCode
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
                registration.getUpdatedAt(),
                null,
                null
        );
    }

    public static BidSourceRegistrationResponse from(
            BidSourceRegistration registration,
            BidSourceSiteIdentificationService.Identification identification
    ) {
        BidSourceRegistrationResponse response = from(registration);
        return new BidSourceRegistrationResponse(
                response.sourceId(),
                response.sourceName(),
                response.siteUrl(),
                response.sourceCode(),
                response.registrationStatus(),
                response.collectionMethod(),
                response.executionEnabled(),
                response.checkStatus(),
                response.detectedCollectionMethod(),
                response.httpStatus(),
                response.contentType(),
                response.checkedAt(),
                response.safeFailureCode(),
                response.createdAt(),
                response.updatedAt(),
                identification.status(),
                identification.matchedSourceCode()
        );
    }
}
