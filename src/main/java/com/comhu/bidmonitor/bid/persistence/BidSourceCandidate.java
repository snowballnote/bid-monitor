package com.comhu.bidmonitor.bid.persistence;

import java.time.Instant;

/** Evidence snapshot independent of bid_notice and its main-list eligibility. */
public record BidSourceCandidate(
        String sourceCode, String sourceNoticeId, String revisionKey,
        String discoveryOperations, String discoveryPaths, String title,
        String listFields, String detailFields, String parsedQualification,
        String detailStatus, String qualificationState, String titleRelevance,
        String promotionState, String decisionReason, String allowedCodes,
        String allowedCodesHash, String parserVersion, String policyVersion,
        Instant firstSeenAt, Instant updatedAt
) { }
