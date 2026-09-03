package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** 사용자가 특정 요구서류에 선택한 회사 파일의 식별자와 표시용 snapshot이다. */
@Getter
@Builder(toBuilder = true)
public class SubmissionDocumentSelection {
    private final Long id;
    private final Long submissionCaseId;
    private final Long requirementId;
    private final Long fileId;
    private final UUID filePublicId;
    private final String originalFilename;
    private final String fileExt;
    private final Instant fileModifiedAt;
    private final Instant fileUpdatedAt;
    private final Instant selectedAt;
}
