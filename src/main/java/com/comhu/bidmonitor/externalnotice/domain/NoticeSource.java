package com.comhu.bidmonitor.externalnotice.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 외부 공지의 출처를 식별하고 출처별 기본 수집 주소를 보관한다.
 * 새 기관을 추가할 때 수집 결과가 어느 사이트에서 왔는지 같은 방식으로 구분하기 위한 값이다.
 */
@Getter
@RequiredArgsConstructor
public enum NoticeSource {

    PRIVACY_PORTAL(
            "PRIVACY_PORTAL",
            "개인정보 포털",
            "https://www.privacy.go.kr/front/bbs/bbsList.do?bbsNo=BBSMSTR_000000000001"
    );

    private final String code;
    private final String displayName;
    private final String listUrl;
}
