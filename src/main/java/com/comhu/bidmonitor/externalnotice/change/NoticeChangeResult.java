package com.comhu.bidmonitor.externalnotice.change;

import lombok.Builder;
import lombok.Value;

/** 판정 이후 알림이나 화면 단계가 사용할 수 있는 최소 변경 결과이다. */
@Value
@Builder
public class NoticeChangeResult {

    String externalId;
    NoticeChangeType changeType;
    Long noticeId;
    String previousFingerprint;
    String currentFingerprint;
}
