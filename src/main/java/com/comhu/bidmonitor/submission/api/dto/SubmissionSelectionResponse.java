package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentSelection;

import java.time.Instant;
import java.util.UUID;

public record SubmissionSelectionResponse(
        Long id,
        Long submissionCaseId,
        Long requirementId,
        Long fileId,
        UUID publicId,
        String originalFilename,
        String fileExt,
        Instant fileModifiedAt,
        Instant updatedAt,
        Instant selectedAt
) {
    public static SubmissionSelectionResponse from(SubmissionDocumentSelection value) {
        return new SubmissionSelectionResponse(
                value.getId(), value.getSubmissionCaseId(), value.getRequirementId(), value.getFileId(),
                value.getFilePublicId(), value.getOriginalFilename(), value.getFileExt(), value.getFileModifiedAt(),
                value.getFileUpdatedAt(), value.getSelectedAt()
        );
    }
}
