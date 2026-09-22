package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:d2b-daily-quota;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class D2bDailyQuotaServiceTests {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant DAY_ONE = Instant.parse("2026-09-22T04:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-09-23T04:00:00Z");

    @Autowired
    private BidSourceStateRepository stateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM bid_source_state");
    }

    @Test
    void reservesFirstCallIncrementsUsageAndStopsAtLimit() {
        seed(2);
        D2bDailyQuotaService service = service(DAY_ONE, 100);

        service.reserve();
        service.reserve();

        BidSourceState state = state();
        assertEquals(2, state.getUsedCalls());
        assertEquals(2, state.getDailyLimit());
        assertEquals("2026-09-22", state.getQuotaDate().toString());
        assertThrows(D2bDailyQuotaExceededException.class, service::reserve);
        assertEquals(2, state().getUsedCalls());
    }

    @Test
    void resetsUsageWhenConfiguredQuotaDateChanges() {
        seed(2);
        service(DAY_ONE, 100).reserve();
        service(DAY_ONE, 100).reserve();

        service(DAY_TWO, 100).reserve();

        assertEquals(1, state().getUsedCalls());
        assertEquals("2026-09-23", state().getQuotaDate().toString());
    }

    @Test
    void concurrentReservationsNeverExceedLimit() throws Exception {
        int limit = 12;
        seed(limit);
        D2bDailyQuotaService service = service(DAY_ONE, 100);
        AtomicInteger successes = new AtomicInteger();
        List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            tasks.add(() -> {
                try {
                    service.reserve();
                    successes.incrementAndGet();
                } catch (D2bDailyQuotaExceededException ignored) {
                    // Expected after the shared row reaches its limit.
                }
                return null;
            });
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(tasks)) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(limit, successes.get());
        assertEquals(limit, state().getUsedCalls());
    }

    @Test
    void failedHttpAttemptsRemainReservedAndAreMeasured() {
        seed(10);
        D2bDailyQuotaService quota = service(DAY_ONE, 100);
        AtomicInteger httpAttempts = new AtomicInteger();
        D2bBidCollector collector = new D2bBidCollector(
                "https://example.test/BidPblancInfoService",
                "test-key",
                uri -> {
                    httpAttempts.incrementAndGet();
                    throw new IllegalStateException("fixture timeout");
                },
                quota::reserve
        );

        D2bBidCollector.CollectionException failure = assertThrows(
                D2bBidCollector.CollectionException.class,
                () -> collector.collectMeasured(java.time.LocalDate.of(2026, 9, 1),
                        java.time.LocalDate.of(2026, 9, 22))
        );

        assertEquals(4, httpAttempts.get());
        assertEquals(4, failure.getApiCallCount());
        assertEquals(4, state().getUsedCalls());
    }

    private D2bDailyQuotaService service(Instant instant, int defaultLimit) {
        return new D2bDailyQuotaService(
                stateRepository, Clock.fixed(instant, ZoneOffset.UTC), SEOUL, defaultLimit
        );
    }

    private void seed(int dailyLimit) {
        stateRepository.save(BidSourceState.builder()
                .sourceCode("D2B")
                .dailyLimit(dailyLimit)
                .build());
    }

    private BidSourceState state() {
        return stateRepository.findBySourceCode("D2B").orElseThrow();
    }
}
