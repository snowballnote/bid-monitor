package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 회사 DB 파일 metadata를 안전한 공개 필드로만 변환한 제출서류 후보다. storage_path는 포함하지 않는다. */
@Getter
@Builder
public class DocumentCandidate {
    private final Long fileId;
    private final UUID publicId;
    private final String originalFilename;
    private final String fileExt;
    private final Instant fileModifiedAt;
    private final Instant updatedAt;
    private final DocumentMatchLevel matchLevel;
    @Builder.Default
    private final List<String> matchReasons = List.of();
}
