package com.comhu.bidmonitor.submission.api.dto;

import java.util.List;

/** PMS 자동 생성과 사용자 체크리스트 생성을 같은 기존 POST 경로에서 지원한다. */
public record CreateSubmissionCaseRequest(
        Long projectId,
        List<CreateSubmissionRequirementRequest> requirements,
        String projectName,
        java.time.LocalDate deadline
) {
}
