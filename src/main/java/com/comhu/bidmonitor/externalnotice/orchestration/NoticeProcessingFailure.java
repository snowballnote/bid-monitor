package com.comhu.bidmonitor.externalnotice.orchestration;

import lombok.Builder;
import lombok.Value;

/** 한 공지의 처리 실패를 전체 수집 중단 없이 호출자에게 전달하는 결과이다. */
@Value
@Builder
public class NoticeProcessingFailure {

    String externalId;
    String title;
    String errorType;
    String errorMessage;
}
