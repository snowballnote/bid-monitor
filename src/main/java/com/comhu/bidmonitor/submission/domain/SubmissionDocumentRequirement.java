package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/** RFP 또는 기존 G2B 문서 분석에서 확보한 제출 필요서류 한 건이다. */
@Getter
@Builder(toBuilder = true)
public class SubmissionDocumentRequirement {
    private final Long id;
    private final Long submissionCaseId;
    private final RequirementCategory category;
    private final String documentName;
    private final boolean required;
    private final String evidenceText;
    private final RequirementSourceType sourceType;
    private final String sourceReference;
    private final Instant createdAt;
}
