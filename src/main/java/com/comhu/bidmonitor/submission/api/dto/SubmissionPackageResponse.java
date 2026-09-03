package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.SubmissionPackage;

import java.util.List;

public record SubmissionPackageResponse(
        SubmissionCaseResponse submissionCase,
        List<SubmissionRequirementResponse> requirements,
        List<SubmissionSelectionResponse> selections
) {
    public static SubmissionPackageResponse from(SubmissionPackage value) {
        return new SubmissionPackageResponse(
                SubmissionCaseResponse.from(value.getSubmissionCase()),
                value.getRequirements().stream().map(SubmissionRequirementResponse::from).toList(),
                value.getSelections().stream().map(SubmissionSelectionResponse::from).toList()
        );
    }
}
