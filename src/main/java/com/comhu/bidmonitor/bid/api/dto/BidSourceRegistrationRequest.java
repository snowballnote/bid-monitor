package com.comhu.bidmonitor.bid.api.dto;

public record BidSourceRegistrationRequest(
        String sourceName,
        String siteUrl,
        Long sourceId,
        String registrationStatus,
        String collectionMethod,
        Boolean executionEnabled,
        String checkStatus,
        String detectedCollectionMethod,
        Integer httpStatus,
        String contentType,
        String checkedAt,
        String safeFailureCode,
        String createdAt,
        String updatedAt
) {

    public void rejectManagedFields() {
        if (sourceId != null || registrationStatus != null || collectionMethod != null
                || executionEnabled != null || checkStatus != null || detectedCollectionMethod != null
                || httpStatus != null || contentType != null || checkedAt != null || safeFailureCode != null
                || createdAt != null || updatedAt != null) {
            throw new IllegalArgumentException("Registration state and execution settings are server-managed.");
        }
    }
}
