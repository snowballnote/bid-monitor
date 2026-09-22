package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import com.comhu.bidmonitor.bid.persistence.service.BidCollectionPersistenceService;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual-bid-coordinator;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "external-notice.scheduler.enabled=false"
})
class ManualBidCollectionCoordinatorTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 21);
    private static final LocalDate END = LocalDate.of(2026, 9, 22);
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final Set<String> LICENSE_CODES = Set.of("6146", "1468");

    @Autowired
    private BidCollectionPersistenceService persistenceService;

    @Autowired
    private BidCollectionRunRepository runRepository;

    @Autowired
    private BidSourceStateRepository stateRepository;

    @Autowired
    private BidCollectionExecutionLockService lockService;

    @Autowired
    private BidNoticeRepository noticeRepository;

    @Autowired
    private ManualBidCollectionSourceRegistry sourceRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Clock clock;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM bid_notice_version");
        jdbcTemplate.update("DELETE FROM bid_notice");
        jdbcTemplate.update("DELETE FROM bid_collection_lock");
        jdbcTemplate.update("DELETE FROM bid_collection_run");
        jdbcTemplate.update("DELETE FROM bid_source_state");
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void runsG2bAloneAndRecordsMeasuredSuccessfulRun() {
        ManualBidCollectionCoordinator coordinator = coordinator(source(
                "G2B", true, List.of(candidate("G2B", "G2B-1", null, "G2B 감리")), 7
        ));

        ManualBidCollectionResult result = coordinator.collect(START, END, LICENSE_CODES);

        assertEquals(ManualBidCollectionResult.Status.SUCCESS, result.status());
        assertTrue(noticeRepository.findByIdentity("G2B", "G2B-1", null).isPresent());
        BidCollectionRun run = latestRun("G2B");
        assertEquals(BidCollectionRun.Status.SUCCESS, run.getStatus());
        assertEquals(7, run.getApiCallCount());
        assertEquals(1, run.getCollectedCount());
        assertEquals(1, run.getNewCount());
        assertNotNull(run.getFinishedAt());
        assertEquals(0, activeLockCount());
    }

    @Test
    void runsKoreaExpresswayAloneAndKeepsUnmeasuredCallCountNull() {
        ManualBidCollectionCoordinator coordinator = coordinator(source(
                "KOREA_EXPRESSWAY", true,
                List.of(candidate("KOREA_EXPRESSWAY", "EX-1", "2", "도로공사 감리")), null
        ));

        coordinator.collect(START, END, LICENSE_CODES);

        assertTrue(noticeRepository.findByIdentity("KOREA_EXPRESSWAY", "EX-1", "2").isPresent());
        assertNull(latestRun("KOREA_EXPRESSWAY").getApiCallCount());
    }

    @Test
    void storesFixtureD2bResultWithoutCallingRealD2bCollector() {
        ManualBidCollectionCoordinator coordinator = coordinator(source(
                "D2B", true, List.of(candidate("D2B", "2026:10:D2B-1", "3", "D2B 감리")), 21
        ));

        coordinator.collect(START, END, LICENSE_CODES);

        BidNotice notice = noticeRepository.findByIdentity("D2B", "2026:10:D2B-1", "3").orElseThrow();
        assertEquals("D2B 감리", notice.getTitle());
        assertEquals(21, latestRun("D2B").getApiCallCount());
    }

    @Test
    void productionRegistryKeepsD2bDisabledAfterQuotaProtectionIsAdded() {
        ManualBidCollectionSource d2b = sourceRegistry.sources().stream()
                .filter(source -> source.sourceCode().equals("D2B"))
                .findFirst()
                .orElseThrow();

        assertFalse(d2b.executionEnabled());
    }

    @Test
    void explicitSourceSelectionCannotEnableD2b() {
        AtomicInteger invocations = new AtomicInteger();
        ManualBidCollectionSource disabledD2b = new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return "D2B";
            }

            @Override
            public boolean executionEnabled() {
                return false;
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                invocations.incrementAndGet();
                return CollectionBatch.unmeasured(List.of());
            }
        };

        assertThrows(IllegalArgumentException.class, () -> coordinator(disabledD2b).collect(
                START, END, LICENSE_CODES, Set.of("D2B")
        ));
        assertEquals(0, invocations.get());
    }

    @Test
    void recordsRunningBeforeSourceExecutionAndThenSuccess() {
        ManualBidCollectionSource inspectingSource = new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return "G2B";
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                BidCollectionRun running = latestRun("G2B");
                assertEquals(BidCollectionRun.Status.RUNNING, running.getStatus());
                assertNull(running.getFinishedAt());
                assertNull(running.getApiCallCount());
                assertEquals(NOW, stateRepository.findBySourceCode("G2B").orElseThrow().getLastAttemptAt());
                return CollectionBatch.unmeasured(List.of(candidate("G2B", "RUNNING-1", null, "공고")));
            }
        };

        coordinator(inspectingSource).collect(START, END, LICENSE_CODES);

        assertEquals(BidCollectionRun.Status.SUCCESS, latestRun("G2B").getStatus());
    }

    @Test
    void distinguishesSuccessfulEmptyResultFromFailure() {
        ManualBidCollectionCoordinator emptyCoordinator = coordinator(source("G2B", true, List.of(), 1));

        ManualBidCollectionResult empty = emptyCoordinator.collect(START, END, LICENSE_CODES);

        assertEquals(ManualBidCollectionResult.Status.SUCCESS, empty.status());
        assertEquals(0, latestRun("G2B").getCollectedCount());
        assertEquals(BidCollectionRun.Status.SUCCESS, latestRun("G2B").getStatus());
    }

    @Test
    void partialSuccessKeepsSuccessfulSourceAndUpdatesStatesIndependently() {
        ManualBidCollectionCoordinator coordinator = coordinator(
                failingSource("G2B"),
                source("KOREA_EXPRESSWAY", true,
                        List.of(candidate("KOREA_EXPRESSWAY", "PARTIAL-1", "1", "성공 공고")), null)
        );

        ManualBidCollectionResult result = coordinator.collect(START, END, LICENSE_CODES);

        assertEquals(ManualBidCollectionResult.Status.PARTIAL_SUCCESS, result.status());
        assertEquals(BidCollectionRun.Status.FAILED, latestRun("G2B").getStatus());
        assertEquals(BidCollectionRun.Status.SUCCESS, latestRun("KOREA_EXPRESSWAY").getStatus());
        assertTrue(noticeRepository.findByIdentity("KOREA_EXPRESSWAY", "PARTIAL-1", "1").isPresent());
        BidSourceState failed = stateRepository.findBySourceCode("G2B").orElseThrow();
        BidSourceState succeeded = stateRepository.findBySourceCode("KOREA_EXPRESSWAY").orElseThrow();
        assertEquals(1, failed.getConsecutiveFailures());
        assertEquals(NOW, failed.getLastFailureAt());
        assertNull(failed.getLastSuccessAt());
        assertEquals(0, succeeded.getConsecutiveFailures());
        assertEquals(NOW, succeeded.getLastSuccessAt());
        assertNull(succeeded.getLastFailureAt());
    }

    @Test
    void allFailuresAreRecordedAndRaised() {
        ManualBidCollectionCoordinator coordinator = coordinator(
                failingSource("G2B"), failingSource("KOREA_EXPRESSWAY")
        );

        ManualBidCollectionException exception = assertThrows(
                ManualBidCollectionException.class,
                () -> coordinator.collect(START, END, LICENSE_CODES)
        );

        assertEquals(ManualBidCollectionResult.Status.FAILED, exception.getResult().status());
        assertEquals(BidCollectionRun.Status.FAILED, latestRun("G2B").getStatus());
        assertEquals(BidCollectionRun.Status.FAILED, latestRun("KOREA_EXPRESSWAY").getStatus());
        assertTrue(noticeRepository.findAll().isEmpty());
        assertEquals(0, activeLockCount());
    }

    @Test
    void recordsMeasuredCallCountWhenD2bCollectionFails() {
        ManualBidCollectionSource measuredFailure = new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return "D2B";
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                throw new MeasuredCollectionException(4, new IllegalStateException("fixture failure"));
            }
        };

        assertThrows(
                ManualBidCollectionException.class,
                () -> coordinator(measuredFailure).collect(START, END, LICENSE_CODES)
        );

        assertEquals(4, latestRun("D2B").getApiCallCount());
    }

    @Test
    void aggregatesNewUnchangedAndUpdatedAcrossRuns() {
        BidQualificationDto notice = candidate("G2B", "COUNTS-1", null, "원본 공고");
        ManualBidCollectionCoordinator first = coordinator(source("G2B", true, List.of(notice), 3));
        first.collect(START, END, LICENSE_CODES);

        ManualBidCollectionCoordinator repeated = coordinator(source("G2B", true, List.of(notice), 3));
        repeated.collect(START, END, LICENSE_CODES);

        BidQualificationDto changed = candidate("G2B", "COUNTS-1", null, "정정 공고");
        ManualBidCollectionCoordinator updated = coordinator(source("G2B", true, List.of(changed), 3));
        updated.collect(START, END, LICENSE_CODES);

        List<BidCollectionRun> runs = runRepository.findBySourceCodeLatestFirst("G2B");
        assertEquals(3, runs.size());
        assertEquals(1, runs.get(0).getChangedCount());
        assertEquals(1, runs.get(1).getCollectedCount());
        assertEquals(0, runs.get(1).getNewCount());
        assertEquals(0, runs.get(1).getChangedCount());
        assertEquals(1, runs.get(2).getNewCount());
    }

    @Test
    void preventsConcurrentDuplicateAcrossCoordinatorInstances() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ManualBidCollectionSource blocking = new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return "G2B";
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                invocations.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return CollectionBatch.unmeasured(List.of(candidate("G2B", "LOCK-1", null, "공고")));
            }
        };
        ManualBidCollectionCoordinator firstCoordinator = coordinator(blocking);
        ManualBidCollectionCoordinator secondCoordinator = coordinator(blocking);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> firstCoordinator.collect(START, END, LICENSE_CODES));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            ManualBidCollectionException duplicate = assertThrows(
                    ManualBidCollectionException.class,
                    () -> secondCoordinator.collect(START, END, LICENSE_CODES)
            );
            assertEquals("ALREADY_RUNNING", duplicate.getResult().sourceResults().getFirst().errorCode());
            assertEquals(1, invocations.get());

            release.countDown();
            assertEquals(ManualBidCollectionResult.Status.SUCCESS, first.get(5, TimeUnit.SECONDS).status());
        }
    }

    private ManualBidCollectionCoordinator coordinator(ManualBidCollectionSource... sources) {
        return new ManualBidCollectionCoordinator(
                persistenceService,
                stateRepository,
                lockService,
                clock,
                List.of(sources)
        );
    }

    private ManualBidCollectionSource source(
            String sourceCode,
            boolean enabled,
            List<BidQualificationDto> candidates,
            Integer apiCallCount
    ) {
        return new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public boolean executionEnabled() {
                return enabled;
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                return new CollectionBatch(candidates, apiCallCount);
            }
        };
    }

    private ManualBidCollectionSource failingSource(String sourceCode) {
        return new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                throw new IllegalStateException("fixture failure");
            }
        };
    }

    private BidCollectionRun latestRun(String sourceCode) {
        return runRepository.findBySourceCodeLatestFirst(sourceCode).getFirst();
    }

    private int activeLockCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bid_collection_lock", Integer.class);
    }

    private BidQualificationDto candidate(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            String title
    ) {
        BidQualificationDto candidate = new BidQualificationDto();
        candidate.setSourceCode(sourceCode);
        candidate.setSourceNoticeId(sourceNoticeId);
        candidate.setRevision(revision);
        candidate.setBidNtceNo("2026-001");
        candidate.setBidNtceNm(title);
        candidate.setNtceInsttNm("테스트 기관");
        candidate.setBidNtceDt("2026-09-22 09:00");
        candidate.setBidClseDt("2026-09-30 18:00");
        candidate.setBidOpeningDt("2026-10-01 10:00");
        candidate.setContractMethod("제한경쟁");
        candidate.setBidForm("전자입찰");
        candidate.setNoticeStatus("정상");
        candidate.setNoticeStatusCode("NORMAL");
        candidate.setLicenseGroups(List.of());
        candidate.setAttachments(List.of());
        return candidate;
    }
}
