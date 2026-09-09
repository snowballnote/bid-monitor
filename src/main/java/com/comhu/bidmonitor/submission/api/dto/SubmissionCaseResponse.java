package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.SubmissionCase;

import java.time.Instant;
import java.util.UUID;

public record SubmissionCaseResponse(
        Long id,
        Long projectId,
        UUID projectPublicId,
        String projectCode,
        String internalBizNo,
        String projectName,
        String bidNoticeNo,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String organizationName, String performanceProjectId, boolean performanceLinkInitialized, java.time.LocalDate deadline
) {
    public static SubmissionCaseResponse from(SubmissionCase value) {
        return new SubmissionCaseResponse(
                value.getId(), value.getProjectId(), value.getProjectPublicId(), value.getProjectCode(),
                value.getInternalBizNo(), value.getProjectName(), value.getBidNoticeNo(),
                value.getStatus().name(), value.getCreatedAt(), value.getUpdatedAt(), value.getOrganizationName(), value.getPerformanceProjectId(), value.isPerformanceLinkInitialized(), value.getDeadline()
        );
    }
}
