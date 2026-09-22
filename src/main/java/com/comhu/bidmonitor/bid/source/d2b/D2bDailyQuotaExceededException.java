package com.comhu.bidmonitor.bid.source.d2b;

import java.time.LocalDate;

public class D2bDailyQuotaExceededException extends IllegalStateException {

    public D2bDailyQuotaExceededException(LocalDate quotaDate) {
        super("D2B daily API quota is exhausted for " + quotaDate + ".");
    }
}
