package com.comhu.bidmonitor.bid.source.d2b.promotion;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record D2bPromotionDecision(
        Optional<QualificationState> qualification,
        Optional<PromotionState> promotion,
        Reason reason,
        Set<String> allowedCodes
) {
    public enum QualificationState { MATCH, MISMATCH, INCOMPLETE, ABSENT }
    public enum PromotionState { MAIN, REVIEW, ARCHIVED }
    public enum Reason {
        DETAIL_FAILED, DETAIL_ITEM_MISSING, SOURCE_FIELDS_MISSING,
        QUALIFICATION_ABSENT, RELATIONSHIP_UNVERIFIED, PARSE_PARTIAL,
        INVALID_GROUPS, MATCHED_GROUP, ALL_GROUPS_MISMATCH
    }

    public D2bPromotionDecision {
        Objects.requireNonNull(qualification, "qualification");
        Objects.requireNonNull(promotion, "promotion");
        Objects.requireNonNull(reason, "reason");
        allowedCodes = Set.copyOf(allowedCodes);
        boolean retryReason = reason == Reason.DETAIL_FAILED || reason == Reason.DETAIL_ITEM_MISSING;
        if (qualification.isEmpty() != promotion.isEmpty()
                || qualification.isEmpty() != retryReason) {
            throw new IllegalArgumentException("Retry evidence cannot have a promotion decision.");
        }
    }

    public boolean retryRequired() {
        return qualification.isEmpty();
    }
}
