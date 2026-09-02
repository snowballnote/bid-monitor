package com.comhu.bidmonitor.classifier;

/** 낙찰방법 분류와 사람이 확인할 근거를 함께 보존한다. */
public record BidAwardMethodResult(
        BidAwardMethodCategory category,
        BidAwardMethodStatus status,
        String reason,
        BidAwardMethodSource source
) {
}
