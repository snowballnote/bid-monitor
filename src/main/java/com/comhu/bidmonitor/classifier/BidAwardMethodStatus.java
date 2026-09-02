package com.comhu.bidmonitor.classifier;

/** 판정 근거의 명확성과 적격심사 탐지 여부를 나타낸다. */
public enum BidAwardMethodStatus {
    CONFIRMED,
    LIKELY,
    NOT_DETECTED,
    UNKNOWN
}
