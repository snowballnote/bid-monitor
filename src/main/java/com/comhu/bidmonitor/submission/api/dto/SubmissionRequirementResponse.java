package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;

public record SubmissionRequirementResponse(
        Long id,
        Long submissionCaseId,
        String category,
        String documentName,
        boolean required,
        String evidenceText,
        String sourceType,
        String sourceReference
) {
    public static SubmissionRequirementResponse from(SubmissionDocumentRequirement value) {
        return new SubmissionRequirementResponse(
                value.getId(), value.getSubmissionCaseId(), value.getCategory().name(), value.getDocumentName(),
                value.isRequired(), value.getEvidenceText(), value.getSourceType().name(), value.getSourceReference()
        );
    }
}
