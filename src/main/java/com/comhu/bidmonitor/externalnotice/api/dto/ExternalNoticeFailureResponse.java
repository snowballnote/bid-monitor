package com.comhu.bidmonitor.externalnotice.api.dto;

import com.comhu.bidmonitor.externalnotice.orchestration.NoticeProcessingFailure;
import lombok.Builder;
import lombok.Value;

/** 수동 수집 응답에 포함할 공지별 실패 요약이다. */
@Value
@Builder
public class ExternalNoticeFailureResponse {

    String externalId;
    String title;
    String errorType;
    String errorMessage;

    public static ExternalNoticeFailureResponse from(NoticeProcessingFailure failure) {
        return ExternalNoticeFailureResponse.builder()
                .externalId(failure.getExternalId())
                .title(failure.getTitle())
                .errorType(failure.getErrorType())
                .errorMessage(failure.getErrorMessage())
                .build();
    }
}
