package com.comhu.bidmonitor.submission.api.dto;

import java.time.LocalDate;

public record UpdateCommonSubmissionDocumentRequest(
        Long fileId,
        LocalDate issuedAt,
        LocalDate expiresAt
) {
}
