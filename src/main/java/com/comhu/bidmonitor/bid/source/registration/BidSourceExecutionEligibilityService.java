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

    boolean isEligible(BidSourceRegistration registration, BidCandidateCollector collector) {
        if (registration == null || collector == null) {
            return false;
        }
        String registrationCode = normalize(registration.getSourceCode());
        String collectorCode = normalize(collector.sourceCode());
        return registration.getRegistrationStatus() == BidSourceRegistration.RegistrationStatus.APPROVED
                && registrationCode != null
                && registrationCode.equals(collectorCode)
                && registration.isExecutionEnabled()
                && registration.getCollectionMethod() != null
                && registration.getCollectionMethod() != BidSourceRegistration.CollectionMethod.UNDETERMINED
                && registration.getCollectionMethod() == registration.getDetectedCollectionMethod()
                && collector.registrationBindingSupported()
                && collector.executionEnabled();
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
