package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidCollectionLock;
import com.comhu.bidmonitor.bid.persistence.BidCollectionLockRepository;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
public class BidCollectionExecutionLockService {

    static final String LEASE_EXPIRED_ERROR = "LOCK_LEASE_EXPIRED";
    static final String ABORTED_ERROR = "COLLECTION_ABORTED";

    private final BidCollectionLockRepository lockRepository;
    private final BidCollectionRunRepository runRepository;
    private final TransactionTemplate transactions;
    private final Duration leaseDuration;

    public BidCollectionExecutionLockService(
            BidCollectionLockRepository lockRepository,
            BidCollectionRunRepository runRepository,
            TransactionTemplate transactions,
            @Value("${bid.collection.lock.lease-seconds:3600}") long leaseSeconds
    ) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("Bid collection lock lease must be positive.");
        }
        this.lockRepository = lockRepository;
        this.runRepository = runRepository;
        this.transactions = transactions;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    public Optional<LockedRun> tryStart(
            String sourceCode,
            LocalDate startDate,
            LocalDate endDate,
            BidCollectionRun.TriggerType triggerType,
            Instant now
    ) {
        if (sourceCode == null || sourceCode.isBlank() || startDate == null || endDate == null
                || endDate.isBefore(startDate) || triggerType == null || now == null) {
            throw new IllegalArgumentException("Valid collection lock identity and start time are required.");
        }
        sourceCode = sourceCode.trim();
        String ownerToken = UUID.randomUUID().toString();
        String normalizedSourceCode = sourceCode;
        try {
            return transactions.execute(status -> {
                Optional<BidCollectionLock> current = lockRepository.findForUpdate(
                        normalizedSourceCode, startDate, endDate
                );
                if (current.isPresent() && current.get().leaseExpiresAt().isAfter(now)) {
                    return Optional.empty();
                }

                BidCollectionLock replacement = new BidCollectionLock(
                        normalizedSourceCode, startDate, endDate, ownerToken, now, now.plus(leaseDuration), null
                );
                if (current.isPresent()) {
                    Long staleRunId = current.get().runId();
                    if (staleRunId != null) {
                        runRepository.failIfRunning(staleRunId, now, LEASE_EXPIRED_ERROR);
                    }
                    lockRepository.replace(replacement);
                } else {
                    lockRepository.insert(replacement);
                }

                BidCollectionRun run = runRepository.save(BidCollectionRun.builder()
                        .sourceCode(normalizedSourceCode)
                        .triggerType(triggerType)
                        .queryStartDate(startDate)
                        .queryEndDate(endDate)
                        .startedAt(now)
                        .status(BidCollectionRun.Status.RUNNING)
                        .apiCallCount(null)
                        .build());
                lockRepository.attachRun(normalizedSourceCode, startDate, endDate, ownerToken, run.getId());
                return Optional.of(new LockedRun(replacement, run));
            });
        } catch (DuplicateKeyException exception) {
            return Optional.empty();
        }
    }

    public void renew(LockedRun lockedRun, Instant now) {
        Boolean renewed = transactions.execute(status -> lockRepository.renew(
                lockedRun.lock().sourceCode(),
                lockedRun.lock().queryStartDate(),
                lockedRun.lock().queryEndDate(),
                lockedRun.lock().ownerToken(),
                now.plus(leaseDuration)
        ));
        if (!Boolean.TRUE.equals(renewed)) {
            throw new IllegalStateException("Bid collection lock ownership was lost.");
        }
    }

    public void completeAndRelease(LockedRun lockedRun, BidCollectionRun completedRun) {
        transactions.executeWithoutResult(status -> {
            if (!lockedRun.run().getId().equals(completedRun.getId())) {
                throw new IllegalArgumentException("Completed run does not own the collection lock.");
            }
            runRepository.update(completedRun);
            if (!release(lockedRun)) {
                throw new IllegalStateException("Bid collection lock ownership was lost before completion.");
            }
        });
    }

    public void abortAndRelease(LockedRun lockedRun, Instant now) {
        transactions.executeWithoutResult(status -> {
            BidCollectionLock expected = lockedRun.lock();
            Optional<BidCollectionLock> current = lockRepository.findForUpdate(
                    expected.sourceCode(), expected.queryStartDate(), expected.queryEndDate()
            );
            if (current.isEmpty() || !current.get().ownerToken().equals(expected.ownerToken())) {
                return;
            }
            runRepository.failIfRunning(lockedRun.run().getId(), now, ABORTED_ERROR);
            if (!release(lockedRun)) {
                throw new IllegalStateException("Bid collection lock could not be released after abort.");
            }
        });
    }

    private boolean release(LockedRun lockedRun) {
        BidCollectionLock lock = lockedRun.lock();
        return lockRepository.release(
                lock.sourceCode(), lock.queryStartDate(), lock.queryEndDate(), lock.ownerToken()
        );
    }

    public record LockedRun(BidCollectionLock lock, BidCollectionRun run) {
    }
}
