package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 회사 DB 파일을 복제하지 않고 현재 사용본 식별자와 행정상 갱신 정보만 보관한다. */
@Getter
@Builder(toBuilder = true)
public class CommonSubmissionDocument {
    private final CommonDocumentType documentType;
    private final String displayName;
    private final Long fileId;
    private final UUID filePublicId;
    private final String originalFilename;
    private final String fileExt;
    private final LocalDate issuedAt;
    private final LocalDate expiresAt;
    private final DocumentRefreshPolicy refreshPolicy;
    private final Integer refreshIntervalMonths;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
}
