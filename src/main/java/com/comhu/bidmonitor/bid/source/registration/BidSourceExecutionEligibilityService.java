package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

/** 등록 기반 collector의 실행 조건을 한 곳에서 판정한다. */
@Service
public class BidSourceExecutionEligibilityService {

    private final BidSourceRegistrationRepository repository;

    public BidSourceExecutionEligibilityService(BidSourceRegistrationRepository repository) {
        this.repository = repository;
    }

    public boolean isEligible(BidCandidateCollector collector) {
        if (collector == null || !collector.registrationBindingSupported()) {
            return false;
        }
        String sourceCode = normalize(collector.sourceCode());
        if (sourceCode == null) {
            return false;
        }
        return repository.findBySourceCode(sourceCode)
                .map(registration -> isEligible(registration, collector))
                .orElse(false);
    }

    public Optional<BidCandidateCollector> findEligibleCollector(
            String sourceCode,
            Collection<BidCandidateCollector> collectors
    ) {
        String normalizedCode = normalize(sourceCode);
        if (normalizedCode == null || collectors == null) {
            return Optional.empty();
        }
        return collectors.stream()
                .filter(BidCandidateCollector::registrationBindingSupported)
                .filter(collector -> normalizedCode.equals(normalize(collector.sourceCode())))
                .filter(this::isEligible)
                .findFirst();
    }

    public Optional<String> activationFailure(
            BidSourceRegistration registration,
            Collection<BidCandidateCollector> collectors
    ) {
        if (registration == null
                || registration.getRegistrationStatus() != BidSourceRegistration.RegistrationStatus.APPROVED) {
            return Optional.of("Only approved registrations can be activated.");
        }
        String sourceCode = normalize(registration.getSourceCode());
        if (sourceCode == null) {
            return Optional.of("A collector binding is required before activation.");
        }
        if (registration.getCollectionMethod() == null
                || registration.getCollectionMethod() == BidSourceRegistration.CollectionMethod.UNDETERMINED) {
            return Optional.of("collectionMethod must be determined before activation.");
        }
        if (registration.getCollectionMethod() != registration.getDetectedCollectionMethod()) {
            return Optional.of("The confirmed and detected collection methods must match before activation.");
        }
        Optional<BidCandidateCollector> collector = findBindingCollector(sourceCode, collectors);
        if (collector.isEmpty()) {
            return Optional.of("No registration-binding collector supports sourceCode.");
        }
        if (!collector.get().executionEnabled()) {
            return Optional.of("The collector is disabled by configuration.");
        }
        return Optional.empty();
    }

    public Optional<BidCandidateCollector> findBindingCollector(
            String sourceCode,
            Collection<BidCandidateCollector> collectors
    ) {
        String normalizedCode = normalize(sourceCode);
        if (normalizedCode == null || collectors == null) {
            return Optional.empty();
        }
        return collectors.stream()
                .filter(BidCandidateCollector::registrationBindingSupported)
                .filter(collector -> normalizedCode.equals(normalize(collector.sourceCode())))
                .findFirst();
    }

    boolean isEligible(BidSourceRegistration registration, BidCandidateCollector collector) {
        if (registration == null || collector == null) {
            return false;
        }
        return registration.isExecutionEnabled()
                && activationFailure(registration, java.util.List.of(collector)).isEmpty();
    }

    public void requireEligible(BidCandidateCollector collector) {
        if (!isEligible(collector)) {
            throw new IllegalStateException("The registered bid source is not eligible for execution.");
        }
    }

    private String normalize(String sourceCode) {
        return sourceCode == null || sourceCode.isBlank() ? null : sourceCode.trim();
    }
}
