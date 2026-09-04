package com.comhu.bidmonitor.submission.api.dto;

/** 사용자가 체크하거나 직접 입력한 제출서류 한 건이다. */
public record CreateSubmissionRequirementRequest(
        String category,
        String documentName,
        String sourceReference
) {
}
