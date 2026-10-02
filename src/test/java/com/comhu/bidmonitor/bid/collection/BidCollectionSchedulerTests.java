package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidCollectionSchedulerTests {

    private static final Instant NOW = Instant.parse("2026-09-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Mock
    private ManualBidCollectionCoordinator coordinator;

    @Mock
    private ManualBidCollectionSourceRegistry sourceRegistry;

    private InMemoryStateRepository stateRepository;
    private BidCollectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        stateRepository = new InMemoryStateRepository();
        scheduler = new BidCollectionScheduler(
                coordinator,
                sourceRegistry,
                stateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(
                        new BidCollectionSourceSchedule("G2B", Duration.ofHours(1), 1),
                        new BidCollectionSourceSchedule("KOREA_EXPRESSWAY", Duration.ofHours(6), 7),
                        new BidCollectionSourceSchedule("KOGAS", Duration.ofHours(24), 7),
                        new BidCollectionSourceSchedule("D2B", Duration.ofHours(1), 1)
                ),
                new GenericPublicPageSchedule(Duration.ofHours(1), 3),
                "Asia/Seoul",
                300,
                3600
        );
    }

    @Test
    void runsOnlyDueEnabledSourceAndUsesConfiguredRange() {
        when(sourceRegistry.sources()).thenReturn(List.of(
                source("G2B", true),
                source("KOREA_EXPRESSWAY", true),
                source("D2B", false),
                source("KOGAS", false)
        ));
        stateRepository.save(BidSourceState.builder()
                .sourceCode("KOREA_EXPRESSWAY")
                .nextRunAt(NOW.plusSeconds(1))
                .build());

        scheduler.runDueCollections();

        verify(coordinator).collectScheduled(TODAY, TODAY, Set.of("6146", "1468"), "G2B");
        verify(coordinator, never()).collectScheduled(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anySet(),
                org.mockito.ArgumentMatchers.eq("KOREA_EXPRESSWAY")
        );
        verify(coordinator, never()).collectScheduled(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anySet(),
                org.mockito.ArgumentMatchers.eq("D2B")
        );
        verify(coordinator, never()).collectScheduled(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anySet(),
                org.mockito.ArgumentMatchers.eq("KOGAS")
        );
        assertEquals(NOW.plus(Duration.ofHours(1)), state("G2B").getNextRunAt());
    }

    @Test
    void sourcesRunIndependentlyAndFailureUsesBackoff() {
        when(sourceRegistry.sources()).thenReturn(List.of(
                source("G2B", true),
                source("KOREA_EXPRESSWAY", true),
                source("KOGAS", true)
        ));
        doThrow(new IllegalStateException("fixture failure"))
                .when(coordinator)
                .collectScheduled(TODAY, TODAY, Set.of("6146", "1468"), "G2B");

        scheduler.runDueCollections();

        verify(coordinator).collectScheduled(
                TODAY.minusDays(6), TODAY, Set.of("6146", "1468"), "KOREA_EXPRESSWAY"
        );
        verify(coordinator).collectScheduled(
                TODAY.minusDays(6), TODAY, Set.of("6146", "1468"), "KOGAS"
        );
        BidSourceState failed = state("G2B");
        assertEquals(1, failed.getConsecutiveFailures());
        assertEquals(NOW.plusSeconds(300), failed.getNextRunAt());
        assertEquals(NOW.plusSeconds(300), failed.getCooldownUntil());
        assertEquals("SCHEDULED_COLLECTION_FAILED", failed.getLastErrorCode());
        assertEquals(NOW.plus(Duration.ofHours(6)), state("KOREA_EXPRESSWAY").getNextRunAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), state("KOGAS").getNextRunAt());

        scheduler.runDueCollections();

        verify(coordinator, times(1)).collectScheduled(
                TODAY, TODAY, Set.of("6146", "1468"), "G2B"
        );
        verify(coordinator, times(1)).collectScheduled(
                TODAY.minusDays(6), TODAY, Set.of("6146", "1468"), "KOREA_EXPRESSWAY"
        );
        verify(coordinator, times(1)).collectScheduled(
                TODAY.minusDays(6), TODAY, Set.of("6146", "1468"), "KOGAS"
        );
    }

    @Test
    void successfulEmptyCoordinatorResultUsesNormalIntervalAndClearsFailureState() {
        when(sourceRegistry.sources()).thenReturn(List.of(source("G2B", true)));
        stateRepository.save(BidSourceState.builder()
                .sourceCode("G2B")
                .consecutiveFailures(3)
                .lastErrorCode("OLD_FAILURE")
                .cooldownUntil(NOW.minusSeconds(1))
                .cooldownSeconds(1200)
                .build());

        scheduler.runDueCollections();

        BidSourceState state = state("G2B");
        assertEquals(0, state.getConsecutiveFailures());
        assertNull(state.getLastErrorCode());
        assertNull(state.getCooldownUntil());
        assertEquals(NOW.plus(Duration.ofHours(1)), state.getNextRunAt());
    }

    @Test
    void runsActiveGenericSourceWithOverlappingDefaultLookbackThroughCoordinator() {
        when(sourceRegistry.sources()).thenReturn(List.of(
                source("CUSTOM_ACTIVE", true, true),
                source("CUSTOM_INACTIVE", false, true)
        ));

        scheduler.runDueCollections();

        verify(coordinator).collectScheduled(
                TODAY.minusDays(2), TODAY, Set.of("6146", "1468"), "CUSTOM_ACTIVE"
        );
        verify(coordinator, never()).collectScheduled(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anySet(),
                org.mockito.ArgumentMatchers.eq("CUSTOM_INACTIVE")
        );
        assertEquals(NOW.plus(Duration.ofHours(1)), state("CUSTOM_ACTIVE").getNextRunAt());
    }

    @Test
    void reflectsGenericActivationAndDeactivationOnFollowingCyclesWithoutRestart() {
        ManualBidCollectionSource activated = source("CUSTOM_DYNAMIC", true, true);
        when(sourceRegistry.sources())
                .thenReturn(List.of())
                .thenReturn(List.of(activated))
                .thenReturn(List.of());

        scheduler.runDueCollections();
        scheduler.runDueCollections();
        scheduler.runDueCollections();

        verify(sourceRegistry, times(3)).sources();
        verify(coordinator, times(1)).collectScheduled(
                TODAY.minusDays(2), TODAY, Set.of("6146", "1468"), "CUSTOM_DYNAMIC"
        );
    }

    @Test
    void isolatesGenericFailureAndContinuesOtherGenericAndFixedSources() {
        when(sourceRegistry.sources()).thenReturn(List.of(
                source("CUSTOM_FAIL", true, true),
                source("CUSTOM_OK", true, true),
                source("G2B", true)
        ));
        doThrow(new IllegalStateException("fixture failure"))
                .when(coordinator)
                .collectScheduled(TODAY.minusDays(2), TODAY, Set.of("6146", "1468"), "CUSTOM_FAIL");

        scheduler.runDueCollections();

        verify(coordinator).collectScheduled(
                TODAY.minusDays(2), TODAY, Set.of("6146", "1468"), "CUSTOM_OK"
        );
        verify(coordinator).collectScheduled(TODAY, TODAY, Set.of("6146", "1468"), "G2B");
        assertEquals(NOW.plusSeconds(300), state("CUSTOM_FAIL").getNextRunAt());
        assertEquals(NOW.plus(Duration.ofHours(1)), state("CUSTOM_OK").getNextRunAt());
    }

    private BidSourceState state(String sourceCode) {
        return stateRepository.findBySourceCode(sourceCode).orElseThrow();
    }

    private ManualBidCollectionSource source(String sourceCode, boolean enabled) {
        return source(sourceCode, enabled, false);
    }

    private ManualBidCollectionSource source(String sourceCode, boolean enabled, boolean genericSchedule) {
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
            public boolean usesGenericSchedule() {
                return genericSchedule;
            }

            @Override
            public CollectionBatch collect(LocalDate startDate, LocalDate endDate, Set<String> codes) {
                throw new AssertionError("Scheduler must not call collectors directly.");
            }
        };
    }

    private static final class InMemoryStateRepository implements BidSourceStateRepository {
        private final Map<String, BidSourceState> states = new LinkedHashMap<>();

        @Override
        public BidSourceState save(BidSourceState state) {
            states.put(state.getSourceCode(), state);
            return state;
        }

        @Override
        public Optional<BidSourceState> findBySourceCode(String sourceCode) {
            return Optional.ofNullable(states.get(sourceCode));
        }

        @Override
        public List<BidSourceState> findAll() {
            return List.copyOf(states.values());
        }

        @Override
        public boolean tryReserveDailyCall(String sourceCode, LocalDate quotaDate, int defaultDailyLimit) {
            throw new AssertionError("Scheduler must not reserve source quota directly.");
        }
    }
}
