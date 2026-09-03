package com.comhu.bidmonitor.submission.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** 회사 DB의 사업을 기준으로 생성한 제출서류 준비 작업이다. 업무 상태만 Biz Assist H2에 저장한다. */
@Getter
@Builder(toBuilder = true)
public class SubmissionCase {
    private final Long id;
    private final Long projectId;
    private final UUID projectPublicId;
    private final String projectCode;
    private final String internalBizNo;
    private final String projectName;
    private final String bidNoticeNo;
    private final SubmissionCaseStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
}
