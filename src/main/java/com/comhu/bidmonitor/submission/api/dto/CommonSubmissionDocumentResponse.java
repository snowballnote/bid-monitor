package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.domain.CommonSubmissionDocument;
import com.comhu.bidmonitor.submission.service.CommonSubmissionDocumentService.ManagedDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 내부 저장경로 없이 공통서류 관리 상태와 회사 파일 식별 reference만 반환한다. */
public record CommonSubmissionDocumentResponse(
        String documentType,
        String displayName,
        Long fileId,
        UUID filePublicId,
        String originalFilename,
        String fileExt,
        LocalDate issuedAt,
        LocalDate expiresAt,
        String refreshPolicy,
        Integer refreshIntervalMonths,
        boolean active,
        String status,
        String statusDisplayName,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommonSubmissionDocumentResponse from(ManagedDocument managed) {
        CommonSubmissionDocument document = managed.document();
        return new CommonSubmissionDocumentResponse(
                document.getDocumentType().name(),
                document.getDisplayName(),
                document.getFileId(),
                document.getFilePublicId(),
                document.getOriginalFilename(),
                document.getFileExt(),
                document.getIssuedAt(),
                document.getExpiresAt(),
                document.getRefreshPolicy().name(),
                document.getRefreshIntervalMonths(),
                document.isActive(),
                managed.status().name(),
                managed.status().getDisplayName(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
