package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.DocumentCandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentCandidateResponse(
        Long fileId,
        UUID publicId,
        String originalFilename,
        String fileExt,
        Instant fileModifiedAt,
        Instant updatedAt,
        String matchLevel,
        List<String> matchReasons
) {
    public static DocumentCandidateResponse from(DocumentCandidate value) {
        return new DocumentCandidateResponse(
                value.getFileId(), value.getPublicId(), value.getOriginalFilename(), value.getFileExt(),
                value.getFileModifiedAt(), value.getUpdatedAt(), value.getMatchLevel().name(), value.getMatchReasons()
        );
    }
}
