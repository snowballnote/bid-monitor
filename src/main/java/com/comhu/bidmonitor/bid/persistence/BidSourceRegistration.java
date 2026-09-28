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
    CheckStatus checkStatus;
    CollectionMethod detectedCollectionMethod;
    Integer httpStatus;
    String contentType;
    Instant checkedAt;
    SafeFailureCode safeFailureCode;
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

    public enum CheckStatus {
        NOT_CHECKED,
        CHECKING,
        REACHABLE,
        UNREACHABLE,
        BLOCKED
    }

    public enum SafeFailureCode {
        INVALID_URL,
        DNS_FAILURE,
        ADDRESS_BLOCKED,
        CONNECTION_TIMEOUT,
        CONNECTION_FAILED,
        REDIRECT_LIMIT_EXCEEDED,
        INVALID_REDIRECT,
        RESPONSE_TOO_LARGE,
        HTTP_ERROR,
        UNSUPPORTED_CONTENT_TYPE,
        INVALID_RESPONSE,
        CHECK_TIMEOUT,
        CHECK_INTERRUPTED,
        CHECK_FAILED
    }
}
