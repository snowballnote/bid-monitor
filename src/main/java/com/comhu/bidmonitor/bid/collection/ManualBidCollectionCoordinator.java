package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import com.comhu.bidmonitor.bid.persistence.service.BidCollectionPersistenceException;
import com.comhu.bidmonitor.bid.persistence.service.BidCollectionPersistenceResult;
import com.comhu.bidmonitor.bid.persistence.service.BidCollectionPersistenceService;
import com.comhu.bidmonitor.bid.persistence.service.BidSourceCollectionResult;
import com.comhu.bidmonitor.bid.persistence.service.BidSourcePersistenceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

@Slf4j
@Service
public class ManualBidCollectionCoordinator {

    private static final String COLLECTION_FAILED = "COLLECTION_FAILED";
    private static final String ALREADY_RUNNING = "ALREADY_RUNNING";

    private final BidCollectionPersistenceService persistenceService;
    private final BidSourceStateRepository stateRepository;
    private final BidCollectionExecutionLockService lockService;
    private final Clock clock;
    private final List<ManualBidCollectionSource> sources;

    @Autowired
    public ManualBidCollectionCoordinator(
            BidCollectionPersistenceService persistenceService,
            BidSourceStateRepository stateRepository,
            BidCollectionExecutionLockService lockService,
            Clock clock,
            ManualBidCollectionSourceRegistry sourceRegistry
    ) {
        this(persistenceService, stateRepository, lockService, clock, sourceRegistry.sources());
    }

    ManualBidCollectionCoordinator(
            BidCollectionPersistenceService persistenceService,
            BidSourceStateRepository stateRepository,
            BidCollectionExecutionLockService lockService,
            Clock clock,
            List<ManualBidCollectionSource> sources
    ) {
        this.persistenceService = persistenceService;
        this.stateRepository = stateRepository;
        this.lockService = lockService;
        this.clock = clock;
        this.sources = validateSources(sources);
    }

    public ManualBidCollectionResult collect(
            LocalDate startDate,
            LocalDate endDate,
            Set<String> allowedLicenseCodes
    ) {
        return collect(startDate, endDate, allowedLicenseCodes, Set.of());
    }

    public ManualBidCollectionResult collect(
            LocalDate startDate,
            LocalDate endDate,
            Set<String> allowedLicenseCodes,
            Set<String> requestedSourceCodes
    ) {
        validateRange(startDate, endDate);
        Set<String> normalizedCodes = allowedLicenseCodes == null ? Set.of() : Set.copyOf(allowedLicenseCodes);
        Set<String> normalizedSources = normalizeRequestedSources(requestedSourceCodes);
        validateRequestedSources(normalizedSources);
        List<String> skippedSources = new ArrayList<>();
        List<BidSourceCollectionResult> collectedResults = new ArrayList<>();
        Map<String, ExecutionContext> executions = new LinkedHashMap<>();
        Map<String, Long> runIdsBySource = new LinkedHashMap<>();
        List<BidCollectionExecutionLockService.LockedRun> acquiredLocks = new ArrayList<>();

        try {
            for (ManualBidCollectionSource source : sources) {
                String sourceCode = source.sourceCode();
                if (!normalizedSources.isEmpty() && !normalizedSources.contains(sourceCode)) {
                    continue;
                }
                if (!source.executionEnabled()) {
                    skippedSources.add(sourceCode);
                    continue;
                }
                Instant startedAt = clock.instant();
                Optional<BidCollectionExecutionLockService.LockedRun> acquired = lockService.tryStart(
                        sourceCode, startDate, endDate, BidCollectionRun.TriggerType.MANUAL, startedAt
                );
                if (acquired.isEmpty()) {
                    lockService.currentRunId(sourceCode, startDate, endDate)
                            .ifPresent(runId -> runIdsBySource.put(sourceCode, runId));
                    collectedResults.add(BidSourceCollectionResult.failure(sourceCode, ALREADY_RUNNING));
                    continue;
                }
                BidCollectionExecutionLockService.LockedRun lockedRun = acquired.get();
                acquiredLocks.add(lockedRun);
                runIdsBySource.put(sourceCode, lockedRun.run().getId());
                executions.put(sourceCode, new ExecutionContext(lockedRun, null));
                try {
                    markAttempt(sourceCode, startedAt);
                    ManualBidCollectionSource.CollectionBatch batch = source.collect(
                            startDate, endDate, normalizedCodes
                    );
                    lockService.renew(lockedRun, clock.instant());
                    executions.put(sourceCode, new ExecutionContext(lockedRun, batch.apiCallCount()));
                    collectedResults.add(BidSourceCollectionResult.success(sourceCode, batch.candidates()));
                } catch (RuntimeException exception) {
                    log.warn("Manual bid collection failed: sourceCode={}, errorType={}",
                            sourceCode, exception.getClass().getSimpleName());
                    Integer apiCallCount = exception instanceof ManualBidCollectionSource.MeasuredCollectionException measured
                            ? measured.getApiCallCount()
                            : null;
                    executions.put(sourceCode, new ExecutionContext(lockedRun, apiCallCount));
                    collectedResults.add(BidSourceCollectionResult.failure(sourceCode, COLLECTION_FAILED));
                }
            }

            if (collectedResults.isEmpty()) {
                throw new IllegalStateException("No bid source is enabled for manual collection.");
            }

            Instant persistenceStartedAt = clock.instant();
            acquiredLocks.forEach(lockedRun -> lockService.renew(lockedRun, persistenceStartedAt));

            BidCollectionPersistenceResult persistenceResult;
            boolean allFailed;
            try {
                persistenceResult = persistenceService.persist(collectedResults, clock.instant());
                allFailed = false;
            } catch (BidCollectionPersistenceException exception) {
                persistenceResult = exception.getResult();
                allFailed = true;
            }

            finishExecutions(executions, persistenceResult.sourceResults());
            ManualBidCollectionResult result = toCoordinatorResult(
                    persistenceResult, startDate, endDate, skippedSources, runIdsBySource
            );
            if (allFailed) {
                throw new ManualBidCollectionException(result);
            }
            return result;
        } finally {
            Instant abortedAt = clock.instant();
            acquiredLocks.forEach(lockedRun -> lockService.abortAndRelease(lockedRun, abortedAt));
        }
    }

    private void finishExecutions(
            Map<String, ExecutionContext> executions,
            List<BidSourcePersistenceResult> sourceResults
    ) {
        Map<String, BidSourcePersistenceResult> resultsBySource = new LinkedHashMap<>();
        sourceResults.forEach(result -> resultsBySource.put(result.sourceCode(), result));
        for (Map.Entry<String, ExecutionContext> entry : executions.entrySet()) {
            String sourceCode = entry.getKey();
            ExecutionContext execution = entry.getValue();
            BidSourcePersistenceResult result = resultsBySource.get(sourceCode);
            if (result == null) {
                result = new BidSourcePersistenceResult(
                        sourceCode, BidSourcePersistenceResult.Status.FAILED, 0, 0, 0, 0,
                        "PERSISTENCE_RESULT_MISSING"
                );
            }
            Instant finishedAt = clock.instant();
            boolean success = result.status() == BidSourcePersistenceResult.Status.SUCCESS;
            BidCollectionRun completedRun = execution.lockedRun().run().toBuilder()
                    .finishedAt(finishedAt)
                    .status(success ? BidCollectionRun.Status.SUCCESS : BidCollectionRun.Status.FAILED)
                    .apiCallCount(execution.apiCallCount())
                    .collectedCount(result.collectedCount())
                    .newCount(result.newCount())
                    .changedCount(result.changedCount())
                    .failureCount(success ? 0 : 1)
                    .errorCode(result.errorCode())
                    .build();
            lockService.completeAndRelease(execution.lockedRun(), completedRun);
            if (success) {
                markSuccess(sourceCode, finishedAt);
            } else {
                markFailure(sourceCode, finishedAt, result.errorCode());
            }
        }
    }

    private void markAttempt(String sourceCode, Instant attemptedAt) {
        BidSourceState current = loadState(sourceCode);
        stateRepository.save(current.toBuilder().lastAttemptAt(attemptedAt).build());
    }

    private void markSuccess(String sourceCode, Instant successfulAt) {
        BidSourceState current = loadState(sourceCode);
        stateRepository.save(current.toBuilder()
                .lastSuccessAt(successfulAt)
                .consecutiveFailures(0)
                .lastErrorCode(null)
                .build());
    }

    private void markFailure(String sourceCode, Instant failedAt, String errorCode) {
        BidSourceState current = loadState(sourceCode);
        stateRepository.save(current.toBuilder()
                .lastFailureAt(failedAt)
                .consecutiveFailures(current.getConsecutiveFailures() + 1)
                .lastErrorCode(errorCode)
                .build());
    }

    private BidSourceState loadState(String sourceCode) {
        return stateRepository.findBySourceCode(sourceCode)
                .orElseGet(() -> BidSourceState.builder().sourceCode(sourceCode).build());
    }

    private ManualBidCollectionResult toCoordinatorResult(
            BidCollectionPersistenceResult persistenceResult,
            LocalDate startDate,
            LocalDate endDate,
            List<String> skippedSources,
            Map<String, Long> runIdsBySource
    ) {
        int successes = persistenceResult.successfulSourceCount();
        int failures = persistenceResult.failedSourceCount();
        ManualBidCollectionResult.Status status = successes == 0
                ? ManualBidCollectionResult.Status.FAILED
                : failures == 0
                ? ManualBidCollectionResult.Status.SUCCESS
                : ManualBidCollectionResult.Status.PARTIAL_SUCCESS;
        return new ManualBidCollectionResult(
                status, startDate, endDate, persistenceResult.sourceResults(), skippedSources, runIdsBySource
        );
    }

    private Set<String> normalizeRequestedSources(Set<String> requestedSourceCodes) {
        if (requestedSourceCodes == null || requestedSourceCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String sourceCode : requestedSourceCodes) {
            if (sourceCode == null || sourceCode.isBlank()) {
                throw new IllegalArgumentException("sourceCodes must not contain blank values.");
            }
            normalized.add(sourceCode.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private void validateRequestedSources(Set<String> requestedSourceCodes) {
        if (requestedSourceCodes.isEmpty()) {
            return;
        }
        Map<String, ManualBidCollectionSource> configured = new LinkedHashMap<>();
        sources.forEach(source -> configured.put(source.sourceCode(), source));
        for (String sourceCode : requestedSourceCodes) {
            ManualBidCollectionSource source = configured.get(sourceCode);
            if (source == null) {
                throw new IllegalArgumentException("Unsupported bid source: " + sourceCode);
            }
            if (!source.executionEnabled()) {
                throw new IllegalArgumentException("Bid source is disabled: " + sourceCode);
            }
        }
    }

    private List<ManualBidCollectionSource> validateSources(List<ManualBidCollectionSource> values) {
        if (values == null) {
            throw new IllegalArgumentException("Bid collection sources are required.");
        }
        Set<String> sourceCodes = new LinkedHashSet<>();
        for (ManualBidCollectionSource source : values) {
            if (source == null || source.sourceCode() == null || source.sourceCode().isBlank()) {
                throw new IllegalArgumentException("Every bid collection source must have a source code.");
            }
            if (!sourceCodes.add(source.sourceCode())) {
                throw new IllegalArgumentException("Duplicate bid collection source: " + source.sourceCode());
            }
        }
        return List.copyOf(values);
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Bid collection date range is invalid.");
        }
    }

    private record ExecutionContext(
            BidCollectionExecutionLockService.LockedRun lockedRun,
            Integer apiCallCount
    ) {
    }
}
