package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.classifier.BidRelevance;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.comhu.bidmonitor.bid.source.d2b.promotion.D2bPromotionDecision.*;
import static com.comhu.bidmonitor.bid.source.d2b.promotion.D2bQualificationEvidence.*;

/** Phase 1 model only: deliberately not connected to the live collection pipeline. */
@Service
public class D2bPromotionEvaluator {
    private final AllowedIndustryCodeProvider allowedIndustryCodeProvider;

    public D2bPromotionEvaluator(AllowedIndustryCodeProvider allowedIndustryCodeProvider) {
        this.allowedIndustryCodeProvider = Objects.requireNonNull(allowedIndustryCodeProvider);
    }

    public D2bPromotionDecision evaluate(D2bQualificationEvidence evidence, BidRelevance relevance) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(relevance, "relevance");
        Set<String> allowedCodes = allowedIndustryCodeProvider.currentCodes();
        if (evidence.detailStatus() != DetailStatus.AVAILABLE) {
            return new D2bPromotionDecision(Optional.empty(), Optional.empty(),
                    evidence.detailStatus() == DetailStatus.FAILED
                            ? Reason.DETAIL_FAILED : Reason.DETAIL_ITEM_MISSING, allowedCodes);
        }
        if (evidence.lcnsLmttList() == null || evidence.industClList() == null
                || evidence.originalConditions() == null) {
            return decision(QualificationState.INCOMPLETE, relevance,
                    Reason.SOURCE_FIELDS_MISSING, allowedCodes);
        }
        boolean rawAbsent = evidence.lcnsLmttList().isBlank()
                && evidence.industClList().isBlank() && evidence.originalConditions().isBlank();
        if (rawAbsent && evidence.licenseGroups().isEmpty()
                && evidence.completeness() != ParseCompleteness.PARTIAL) {
            return decision(QualificationState.ABSENT, relevance,
                    Reason.QUALIFICATION_ABSENT, allowedCodes);
        }
        if (evidence.completeness() != ParseCompleteness.VERIFIED_COMPLETE) {
            return decision(QualificationState.INCOMPLETE, relevance,
                    evidence.completeness() == ParseCompleteness.PARTIAL
                            ? Reason.PARSE_PARTIAL : Reason.RELATIONSHIP_UNVERIFIED, allowedCodes);
        }
        List<List<String>> groups = evidence.licenseGroups();
        if (rawAbsent || groups.isEmpty() || groups.stream().anyMatch(group -> group.isEmpty()
                || group.stream().anyMatch(code -> code == null || !code.matches("[0-9]{4}")))) {
            return decision(QualificationState.INCOMPLETE, relevance,
                    Reason.INVALID_GROUPS, allowedCodes);
        }
        // OR between alternatives; AND within each alternative. No title-based qualification.
        boolean match = groups.stream().anyMatch(group -> group.stream().allMatch(allowedCodes::contains));
        return decision(match ? QualificationState.MATCH : QualificationState.MISMATCH,
                relevance, match ? Reason.MATCHED_GROUP : Reason.ALL_GROUPS_MISMATCH, allowedCodes);
    }

    private D2bPromotionDecision decision(
            QualificationState qualification, BidRelevance relevance, Reason reason, Set<String> allowedCodes
    ) {
        PromotionState promotion = switch (qualification) {
            case MATCH -> PromotionState.MAIN;
            case MISMATCH -> PromotionState.ARCHIVED;
            case INCOMPLETE, ABSENT -> relevance == BidRelevance.RELEVANT
                    ? PromotionState.MAIN : PromotionState.REVIEW;
        };
        return new D2bPromotionDecision(Optional.of(qualification), Optional.of(promotion), reason, allowedCodes);
    }
}
