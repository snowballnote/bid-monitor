package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class D2bDailyQuotaService {

    private static final String SOURCE_CODE = "D2B";

    private final BidSourceStateRepository stateRepository;
    private final Clock clock;
    private final ZoneId quotaZone;
    private final int defaultDailyLimit;

    @Autowired
    public D2bDailyQuotaService(
            BidSourceStateRepository stateRepository,
            Clock clock,
            @Value("${d2b.api.quota-zone:Asia/Seoul}") String quotaZone,
            @Value("${d2b.api.daily-limit:100}") int defaultDailyLimit
    ) {
        this(stateRepository, clock, ZoneId.of(quotaZone), defaultDailyLimit);
    }

    D2bDailyQuotaService(
            BidSourceStateRepository stateRepository,
            Clock clock,
            ZoneId quotaZone,
            int defaultDailyLimit
    ) {
        if (defaultDailyLimit < 0) {
            throw new IllegalArgumentException("D2B daily limit must not be negative.");
        }
        this.stateRepository = stateRepository;
        this.clock = clock;
        this.quotaZone = quotaZone;
        this.defaultDailyLimit = defaultDailyLimit;
    }

    public void reserve() {
        LocalDate quotaDate = LocalDate.now(clock.withZone(quotaZone));
        if (!stateRepository.tryReserveDailyCall(SOURCE_CODE, quotaDate, defaultDailyLimit)) {
            throw new D2bDailyQuotaExceededException(quotaDate);
        }
    }
}
