package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class BidCollectionScheduler {

    private static final Set<String> DEFAULT_LICENSE_CODES = Set.of("6146", "1468");

    private final ManualBidCollectionCoordinator coordinator;
    private final ManualBidCollectionSourceRegistry sourceRegistry;
    private final BidSourceStateRepository stateRepository;
    private final Clock clock;
    private final ZoneId zoneId;
    private final Duration failureBaseDelay;
    private final Duration failureMaxDelay;
    private final Map<String, BidCollectionSourceSchedule> schedules;

    public BidCollectionScheduler(
            ManualBidCollectionCoordinator coordinator,
            ManualBidCollectionSourceRegistry sourceRegistry,
            BidSourceStateRepository stateRepository,
            Clock clock,
            List<BidCollectionSourceSchedule> schedules,
            @Value("${bid.collection.scheduler.zone-id:Asia/Seoul}") String zoneId,
            @Value("${bid.collection.scheduler.failure-base-delay-seconds:300}") long failureBaseDelaySeconds,
            @Value("${bid.collection.scheduler.failure-max-delay-seconds:3600}") long failureMaxDelaySeconds
    ) {
        this.coordinator = coordinator;
        this.sourceRegistry = sourceRegistry;
        this.stateRepository = stateRepository;
        this.clock = clock;
        this.zoneId = ZoneId.of(zoneId);
        this.failureBaseDelay = positiveDuration(failureBaseDelaySeconds, "failureBaseDelaySeconds");
        this.failureMaxDelay = positiveDuration(failureMaxDelaySeconds, "failureMaxDelaySeconds");
        if (failureMaxDelay.compareTo(failureBaseDelay) < 0) {
            throw new IllegalArgumentException("failureMaxDelay must not be shorter than failureBaseDelay");
        }
        this.schedules = scheduleMap(schedules);
    }

    @Scheduled(
            fixedDelayString = "${bid.collection.scheduler.scan-interval-ms:60000}",
            initialDelayString = "${bid.collection.scheduler.initial-delay-ms:10000}"
    )
    public void runDueCollections() {
        Instant scanTime = clock.instant();
        for (ManualBidCollectionSource source : sourceRegistry.sources()) {
            BidCollectionSourceSchedule schedule = schedules.get(source.sourceCode());
            if (schedule == null || !source.executionEnabled()) {
                continue;
            }

            BidSourceState state = loadState(source.sourceCode());
            if (state.getNextRunAt() != null && state.getNextRunAt().isAfter(scanTime)) {
                continue;
            }
            runSource(source.sourceCode(), schedule, scanTime, state);
        }
    }

    private void runSource(
            String sourceCode,
            BidCollectionSourceSchedule schedule,
            Instant attemptStartedAt,
            BidSourceState stateBeforeAttempt
    ) {
        LocalDate endDate = LocalDate.ofInstant(attemptStartedAt, zoneId);
        LocalDate startDate = endDate.minusDays(schedule.lookbackDays() - 1L);
        try {
            coordinator.collectScheduled(startDate, endDate, DEFAULT_LICENSE_CODES, sourceCode);
            Instant completedAt = clock.instant();
            BidSourceState current = loadState(sourceCode);
            stateRepository.save(current.toBuilder()
                    .consecutiveFailures(0)
                    .lastErrorCode(null)
                    .nextRunAt(completedAt.plus(schedule.interval()))
                    .cooldownUntil(null)
                    .cooldownSeconds(0)
                    .build());
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            BidSourceState current = loadState(sourceCode);
            boolean coordinatorRecordedFailure = current.getLastFailureAt() != null
                    && !current.getLastFailureAt().isBefore(attemptStartedAt);
            int failures = coordinatorRecordedFailure
                    ? Math.max(1, current.getConsecutiveFailures())
                    : Math.max(1, stateBeforeAttempt.getConsecutiveFailures() + 1);
            Duration retryDelay = failureDelay(failures);
            BidSourceState.BidSourceStateBuilder nextState = current.toBuilder()
                    .consecutiveFailures(failures)
                    .nextRunAt(failedAt.plus(retryDelay))
                    .cooldownUntil(failedAt.plus(retryDelay))
                    .cooldownSeconds(retryDelay.toSeconds());
            if (!coordinatorRecordedFailure) {
                nextState.lastFailureAt(failedAt).lastErrorCode("SCHEDULED_COLLECTION_FAILED");
            }
            stateRepository.save(nextState.build());
            log.warn("Scheduled bid collection failed: sourceCode={}, failureType={}",
                    sourceCode, exception.getClass().getSimpleName());
        }
    }

    private BidSourceState loadState(String sourceCode) {
        return stateRepository.findBySourceCode(sourceCode)
                .orElseGet(() -> BidSourceState.builder().sourceCode(sourceCode).build());
    }

    private Duration failureDelay(int consecutiveFailures) {
        int exponent = Math.min(Math.max(0, consecutiveFailures - 1), 30);
        long multiplier = 1L << exponent;
        long baseSeconds = failureBaseDelay.toSeconds();
        long maxSeconds = failureMaxDelay.toSeconds();
        long seconds = baseSeconds > maxSeconds / multiplier
                ? maxSeconds
                : Math.min(maxSeconds, baseSeconds * multiplier);
        return Duration.ofSeconds(seconds);
    }

    private static Duration positiveDuration(long seconds, String name) {
        if (seconds <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return Duration.ofSeconds(seconds);
    }

    private static Map<String, BidCollectionSourceSchedule> scheduleMap(
            List<BidCollectionSourceSchedule> schedules
    ) {
        Map<String, BidCollectionSourceSchedule> result = new LinkedHashMap<>();
        for (BidCollectionSourceSchedule schedule : schedules) {
            if (result.putIfAbsent(schedule.sourceCode(), schedule) != null) {
                throw new IllegalArgumentException("Duplicate collection schedule: " + schedule.sourceCode());
            }
        }
        return Map.copyOf(result);
    }
}
