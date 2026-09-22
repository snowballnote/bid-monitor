package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidCollectionLockRepository;
import com.comhu.bidmonitor.bid.persistence.BidCollectionLock;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bid-collection-lock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false",
        "bid.collection.lock.lease-seconds=60"
})
class BidCollectionExecutionLockServiceTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 21);
    private static final LocalDate END = LocalDate.of(2026, 9, 22);
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");

    @Autowired
    private BidCollectionExecutionLockService lockService;

    @Autowired
    private BidCollectionLockRepository lockRepository;

    @Autowired
    private BidCollectionRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM bid_collection_lock");
        jdbcTemplate.update("DELETE FROM bid_collection_run");
    }

    @Test
    void concurrentRequestsForSameKeyHaveSingleOwner() throws Exception {
        List<java.util.concurrent.Callable<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            attempts.add(() -> lockService.tryStart(
                    "G2B", START, END, BidCollectionRun.TriggerType.MANUAL, NOW
            ).isPresent());
        }

        long acquired;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            acquired = executor.invokeAll(attempts).stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
        }

        assertEquals(1, acquired);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bid_collection_lock", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bid_collection_run", Integer.class));
    }

    @Test
    void differentSourcesAcquireIndependently() {
        assertTrue(start("G2B", NOW).isPresent());
        assertTrue(start("KOREA_EXPRESSWAY", NOW).isPresent());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bid_collection_lock", Integer.class));
    }

    @Test
    void normalCompletionReleasesLockAndAllowsNextRun() {
        BidCollectionExecutionLockService.LockedRun locked = start("G2B", NOW).orElseThrow();
        BidCollectionRun completed = locked.run().toBuilder()
                .finishedAt(NOW.plusSeconds(1))
                .status(BidCollectionRun.Status.SUCCESS)
                .build();

        lockService.completeAndRelease(locked, completed);

        assertEquals(0, lockCount());
        assertEquals(BidCollectionRun.Status.SUCCESS,
                runRepository.findById(locked.run().getId()).orElseThrow().getStatus());
        assertTrue(start("G2B", NOW.plusSeconds(2)).isPresent());
    }

    @Test
    void failureCleanupReleasesLockAndMarksRunFailed() {
        BidCollectionExecutionLockService.LockedRun locked = start("G2B", NOW).orElseThrow();

        lockService.abortAndRelease(locked, NOW.plusSeconds(1));

        BidCollectionRun failed = runRepository.findById(locked.run().getId()).orElseThrow();
        assertEquals(BidCollectionRun.Status.FAILED, failed.getStatus());
        assertEquals(BidCollectionExecutionLockService.ABORTED_ERROR, failed.getErrorCode());
        assertEquals(0, lockCount());
    }

    @Test
    void expiredLeaseIsReacquiredAndPreviousRunningRunBecomesFailed() {
        BidCollectionExecutionLockService.LockedRun stale = start("G2B", NOW).orElseThrow();

        BidCollectionExecutionLockService.LockedRun replacement = start(
                "G2B", NOW.plusSeconds(61)
        ).orElseThrow();

        BidCollectionRun staleRun = runRepository.findById(stale.run().getId()).orElseThrow();
        assertEquals(BidCollectionRun.Status.FAILED, staleRun.getStatus());
        assertEquals(BidCollectionExecutionLockService.LEASE_EXPIRED_ERROR, staleRun.getErrorCode());
        assertEquals(NOW.plusSeconds(61), staleRun.getFinishedAt());
        assertEquals(BidCollectionRun.Status.RUNNING,
                runRepository.findById(replacement.run().getId()).orElseThrow().getStatus());
        assertEquals(1, lockCount());
    }

    @Test
    void nonOwnerCannotReleaseLock() {
        BidCollectionExecutionLockService.LockedRun locked = start("G2B", NOW).orElseThrow();

        boolean released = lockRepository.release("G2B", START, END, "not-the-owner");
        BidCollectionLock wrongOwner = new BidCollectionLock(
                "G2B", START, END, "not-the-owner", NOW, NOW.plusSeconds(60), locked.run().getId()
        );
        lockService.abortAndRelease(
                new BidCollectionExecutionLockService.LockedRun(wrongOwner, locked.run()),
                NOW.plusSeconds(1)
        );

        assertFalse(released);
        assertEquals(1, lockCount());
        assertFalse(start("G2B", NOW.plusSeconds(1)).isPresent());
        assertEquals(BidCollectionRun.Status.RUNNING,
                runRepository.findById(locked.run().getId()).orElseThrow().getStatus());
    }

    private java.util.Optional<BidCollectionExecutionLockService.LockedRun> start(
            String sourceCode,
            Instant now
    ) {
        return lockService.tryStart(sourceCode, START, END, BidCollectionRun.TriggerType.MANUAL, now);
    }

    private int lockCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bid_collection_lock", Integer.class);
    }
}
