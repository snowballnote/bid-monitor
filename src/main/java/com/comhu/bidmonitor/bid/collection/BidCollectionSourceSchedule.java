package com.comhu.bidmonitor.bid.collection;

import java.time.Duration;

public record BidCollectionSourceSchedule(String sourceCode, Duration interval, int lookbackDays) {

    public BidCollectionSourceSchedule {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode must not be blank");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("lookbackDays must be at least 1");
        }
    }
}
