package com.comhu.bidmonitor.bid.persistence.service;

import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidNoticeSaveResult;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BidCollectionPersistenceService {

    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,100}");
    private static final Pattern LOG_URL_PATTERN = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern LOG_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|token|secret|authorization|credential)\\s*[:=]\\s*\\S+"
    );
    private static final String COLLECTION_FAILED = "COLLECTION_FAILED";
    private static final String PERSISTENCE_FAILED = "PERSISTENCE_FAILED";

    private final BidNoticeRepository noticeRepository;
    private final BidQualificationNoticeMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public BidCollectionPersistenceService(
            BidNoticeRepository noticeRepository,
            BidQualificationNoticeMapper mapper,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.noticeRepository = noticeRepository;
        this.mapper = mapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BidCollectionPersistenceResult persist(List<BidSourceCollectionResult> sourceResults) {
        return persist(sourceResults, clock.instant());
    }

    public BidCollectionPersistenceResult persist(
            List<BidSourceCollectionResult> sourceResults,
            Instant collectedAt
    ) {
        if (sourceResults == null || sourceResults.isEmpty()) {
            throw new IllegalArgumentException("At least one bid source result is required.");
        }
        if (collectedAt == null) {
            throw new IllegalArgumentException("collectedAt is required.");
        }

        List<BidSourcePersistenceResult> results = new ArrayList<>();
        for (BidSourceCollectionResult sourceResult : sourceResults) {
            if (sourceResult == null) {
                results.add(failure("UNKNOWN", PERSISTENCE_FAILED));
            } else if (sourceResult.status() == BidSourceCollectionResult.Status.FAILED) {
                results.add(failure(sourceResult.sourceCode(), safeErrorCode(sourceResult.errorCode())));
            } else {
                results.add(persistSource(sourceResult, collectedAt));
            }
        }

        BidCollectionPersistenceResult result = new BidCollectionPersistenceResult(results);
        if (result.successfulSourceCount() == 0) {
            throw new BidCollectionPersistenceException(result);
        }
        return result;
    }

    private BidSourcePersistenceResult persistSource(
            BidSourceCollectionResult sourceResult,
            Instant collectedAt
    ) {
        try {
            return transactionTemplate.execute(status -> saveSource(sourceResult, collectedAt));
        } catch (RuntimeException exception) {
            log.warn("Bid source persistence failed: sourceCode={}, errorType={}",
                    sourceResult.sourceCode(), exception.getClass().getSimpleName());
            return failure(sourceResult.sourceCode(), PERSISTENCE_FAILED);
        }
    }

    private BidSourcePersistenceResult saveSource(
            BidSourceCollectionResult sourceResult,
            Instant collectedAt
    ) {
        int newCount = 0;
        int changedCount = 0;
        int unchangedCount = 0;
        for (BidQualificationDto candidate : sourceResult.candidates()) {
            BidNotice mapped;
            try {
                if (candidate == null || !sourceResult.sourceCode().equals(candidate.getSourceCode())) {
                    throw new IllegalArgumentException("Candidate source does not match its source result.");
                }
                mapped = mapper.map(candidate, collectedAt);
            } catch (RuntimeException exception) {
                logCandidateFailure(sourceResult.sourceCode(), candidate, "mapper", exception);
                throw exception;
            }
            BidNoticeSaveResult saved;
            try {
                saved = noticeRepository.save(mapped);
            } catch (RuntimeException exception) {
                logCandidateFailure(sourceResult.sourceCode(), candidate, "repository", exception);
                throw exception;
            }
            switch (saved.changeType()) {
                case NEW -> newCount++;
                case UPDATED -> changedCount++;
                case UNCHANGED -> unchangedCount++;
            }
        }
        return new BidSourcePersistenceResult(
                sourceResult.sourceCode(),
                BidSourcePersistenceResult.Status.SUCCESS,
                sourceResult.candidates().size(),
                newCount,
                changedCount,
                unchangedCount,
                null
        );
    }

    private void logCandidateFailure(
            String sourceCode,
            BidQualificationDto candidate,
            String stage,
            RuntimeException exception
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "Bid candidate persistence failed: sourceCode={}, sourceNoticeId={}, revision={}, "
                        + "stage={}, errorType={}, errorMessage={}",
                sourceCode,
                candidate == null ? null : candidate.getSourceNoticeId(),
                candidate == null ? null : candidate.getRevision(),
                stage,
                exception.getClass().getSimpleName(),
                safeLogMessage(exception.getMessage())
        );
    }

    private String safeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "(no message)";
        }
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ").strip();
        singleLine = LOG_URL_PATTERN.matcher(singleLine).replaceAll("[REDACTED_URL]");
        singleLine = LOG_SECRET_PATTERN.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "...";
    }

    private BidSourcePersistenceResult failure(String sourceCode, String errorCode) {
        return new BidSourcePersistenceResult(
                sourceCode,
                BidSourcePersistenceResult.Status.FAILED,
                0,
                0,
                0,
                0,
                errorCode
        );
    }

    private String safeErrorCode(String errorCode) {
        if (errorCode == null || !SAFE_ERROR_CODE.matcher(errorCode).matches()) {
            return COLLECTION_FAILED;
        }
        return errorCode;
    }
}
