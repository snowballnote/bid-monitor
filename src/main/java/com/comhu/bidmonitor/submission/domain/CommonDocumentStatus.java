package com.comhu.bidmonitor.submission.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 관리자에게 노출하는 공통서류의 사용 가능 상태다. */
@Getter
@RequiredArgsConstructor
public enum CommonDocumentStatus {
    AVAILABLE("사용 가능"),
    REFRESH_RECOMMENDED("갱신 권고"),
    EXPIRING_SOON("만료 임박"),
    EXPIRED("만료"),
    UNREGISTERED("미등록");

    private final String displayName;
}
