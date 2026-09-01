package com.comhu.bidmonitor.externalnotice.api.dto;

import lombok.Builder;
import lombok.Value;

/** 외부공지 API 오류를 클라이언트가 일관된 JSON으로 확인하기 위한 응답이다. */
@Value
@Builder
public class ExternalNoticeErrorResponse {

    int status;
    String error;
    String message;
}
