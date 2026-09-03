package com.comhu.bidmonitor.submission.api.dto;

import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort.PmsProjectSummary;

/** 내부 projectId 외에는 사업 선택 화면에 필요한 최소 필드만 공개한다. */
public record SubmissionProjectResponse(
        Long projectId,
        String projectName,
        String organizationName,
        String bidNoticeNo
) {
    public static SubmissionProjectResponse from(PmsProjectSummary value) {
        return new SubmissionProjectResponse(
                value.projectId(), value.noticeName(), value.organizationName(), value.bidNoticeNo()
        );
    }
}
