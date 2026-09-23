package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class BidSourceRegistration {

    Long sourceId;
    String sourceName;
    String siteUrl;
    RegistrationStatus registrationStatus;
    CollectionMethod collectionMethod;
    boolean executionEnabled;
    Instant createdAt;
    Instant updatedAt;

    public enum RegistrationStatus {
        PENDING_REVIEW,
        UNDER_REVIEW,
        APPROVED,
        REJECTED
    }

    public enum CollectionMethod {
        UNDETERMINED,
        OFFICIAL_API,
        PUBLIC_PAGE,
        RSS
    }
}
