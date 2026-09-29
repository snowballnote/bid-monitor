package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;

public record BidSourceRegistrationAudit(
        Long auditId,
        long sourceId,
        Action action,
        BidSourceRegistration.RegistrationStatus previousStatus,
        BidSourceRegistration.RegistrationStatus newStatus,
        String previousSourceCode,
        String newSourceCode,
        boolean previousExecutionEnabled,
        boolean newExecutionEnabled,
        String actor,
        Instant createdAt
) {
    public enum Action {
        REVIEW_STATUS_CHANGED,
        SOURCE_BOUND,
        ACTIVATED,
        DEACTIVATED
    }
}
