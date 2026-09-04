package com.comhu.bidmonitor.submission.domain;

/** 공통서류를 날짜와 무관하게 쓰는지, 만료일 또는 정기 주기로 갱신하는지 구분한다. */
public enum DocumentRefreshPolicy {
    NONE,
    EXPIRATION_BASED,
    PERIODIC
}
