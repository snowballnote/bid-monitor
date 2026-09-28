package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class BidSourceRegistrationService {

    private static final int MAX_SOURCE_NAME_LENGTH = 200;

    private final BidSourceRegistrationRepository repository;
    private final BidSourceUrlNormalizer urlNormalizer;
    private final BidSourceAvailabilityChecker availabilityChecker;
    private final Clock clock;

    public BidSourceRegistrationService(
            BidSourceRegistrationRepository repository,
            BidSourceUrlNormalizer urlNormalizer,
            BidSourceAvailabilityChecker availabilityChecker,
            Clock clock
    ) {
        this.repository = repository;
        this.urlNormalizer = urlNormalizer;
        this.availabilityChecker = availabilityChecker;
        this.clock = clock;
    }

    @Transactional
    public BidSourceRegistration register(String sourceName, String siteUrl) {
        String normalizedName = normalizeName(sourceName);
        String normalizedUrl = urlNormalizer.normalize(siteUrl);
        Instant now = clock.instant();
        try {
            return repository.save(BidSourceRegistration.builder()
                    .sourceName(normalizedName)
                    .siteUrl(normalizedUrl)
                    .registrationStatus(BidSourceRegistration.RegistrationStatus.PENDING_REVIEW)
                    .collectionMethod(BidSourceRegistration.CollectionMethod.UNDETERMINED)
                    .executionEnabled(false)
                    .checkStatus(BidSourceRegistration.CheckStatus.NOT_CHECKED)
                    .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.UNDETERMINED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DuplicateKeyException exception) {
            throw new DuplicateBidSourceUrlException();
        }
    }

    public List<BidSourceRegistration> findAll() {
        return repository.findAllLatestFirst();
    }

    public BidSourceRegistration findById(long sourceId) {
        return repository.findById(sourceId)
                .orElseThrow(BidSourceRegistrationNotFoundException::new);
    }

    public BidSourceRegistration check(long sourceId) {
        BidSourceRegistration current = findById(sourceId);
        if (current.getRegistrationStatus() != BidSourceRegistration.RegistrationStatus.PENDING_REVIEW
                && current.getRegistrationStatus() != BidSourceRegistration.RegistrationStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException(
                    "Only pending or under-review registrations can be checked."
            );
        }
        if (!repository.markCheckStarted(sourceId)) {
            BidSourceRegistration latest = findById(sourceId);
            if (latest.getCheckStatus() == BidSourceRegistration.CheckStatus.CHECKING) {
                throw new IllegalArgumentException("A site availability check is already running.");
            }
            throw new IllegalArgumentException("The registration is no longer eligible for checking.");
        }

        BidSourceCheckResult result;
        try {
            result = availabilityChecker.check(current.getSiteUrl());
        } catch (RuntimeException exception) {
            result = new BidSourceCheckResult(
                    BidSourceRegistration.CheckStatus.UNREACHABLE,
                    BidSourceRegistration.CollectionMethod.UNDETERMINED,
                    null,
                    null,
                    clock.instant(),
                    BidSourceRegistration.SafeFailureCode.CHECK_FAILED
            );
        }
        if (!repository.updateCheckResult(sourceId, result)) {
            throw new IllegalStateException("The site availability check result could not be saved.");
        }
        return findById(sourceId);
    }

    @Transactional
    public BidSourceRegistration review(
            long sourceId,
            String registrationStatus,
            String collectionMethod
    ) {
        BidSourceRegistration current = findById(sourceId);
        BidSourceRegistration.RegistrationStatus targetStatus = parseRegistrationStatus(registrationStatus);
        BidSourceRegistration.CollectionMethod targetMethod = collectionMethod == null
                ? current.getCollectionMethod()
                : parseCollectionMethod(collectionMethod);

        validateTransition(current.getRegistrationStatus(), targetStatus);
        if (targetStatus == BidSourceRegistration.RegistrationStatus.APPROVED
                && targetMethod == BidSourceRegistration.CollectionMethod.UNDETERMINED) {
            throw new IllegalArgumentException(
                    "collectionMethod must be determined before approval."
            );
        }

        boolean updated = repository.updateReview(
                sourceId,
                current.getRegistrationStatus(),
                targetStatus,
                targetMethod,
                clock.instant()
        );
        if (!updated) {
            repository.findById(sourceId)
                    .orElseThrow(BidSourceRegistrationNotFoundException::new);
            throw new IllegalArgumentException("Registration status changed during review.");
        }
        return findById(sourceId);
    }

    private BidSourceRegistration.RegistrationStatus parseRegistrationStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("registrationStatus is required.");
        }
        try {
            return BidSourceRegistration.RegistrationStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("registrationStatus is invalid.");
        }
    }

    private BidSourceRegistration.CollectionMethod parseCollectionMethod(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("collectionMethod is invalid.");
        }
        try {
            return BidSourceRegistration.CollectionMethod.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("collectionMethod is invalid.");
        }
    }

    private void validateTransition(
            BidSourceRegistration.RegistrationStatus current,
            BidSourceRegistration.RegistrationStatus target
    ) {
        boolean allowed = (current == BidSourceRegistration.RegistrationStatus.PENDING_REVIEW
                && target == BidSourceRegistration.RegistrationStatus.UNDER_REVIEW)
                || (current == BidSourceRegistration.RegistrationStatus.UNDER_REVIEW
                && (target == BidSourceRegistration.RegistrationStatus.APPROVED
                || target == BidSourceRegistration.RegistrationStatus.REJECTED));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Registration status cannot transition from " + current + " to " + target + "."
            );
        }
    }

    private String normalizeName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName is required.");
        }
        String normalized = sourceName.trim();
        if (normalized.length() > MAX_SOURCE_NAME_LENGTH) {
            throw new IllegalArgumentException("sourceName must not exceed 200 characters.");
        }
        return normalized;
    }
}
