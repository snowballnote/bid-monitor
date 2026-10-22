package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Collects filtered notices from active registered sources without persisting notice data. */
@Service
public class LiveBidCollectionService {

    private static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
    private static final String COLLECTION_FAILED = "COLLECTION_FAILED";

    private final ManualBidCollectionSourceRegistry sourceRegistry;
    private final BidSourceRegistrationRepository registrationRepository;
    private final AllowedIndustryCodeProvider allowedIndustryCodeProvider;

    public LiveBidCollectionService(
            ManualBidCollectionSourceRegistry sourceRegistry,
            BidSourceRegistrationRepository registrationRepository,
            AllowedIndustryCodeProvider allowedIndustryCodeProvider
    ) {
        this.sourceRegistry = sourceRegistry;
        this.registrationRepository = registrationRepository;
        this.allowedIndustryCodeProvider = allowedIndustryCodeProvider;
    }

    public LiveBidCollectionResult collect(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        Map<String, ManualBidCollectionSource> availableSources = availableSources();
        Set<String> allowedIndustryCodes = allowedIndustryCodeProvider.currentCodes();
        List<LiveBidCollectionResult.SourceResult> results = new ArrayList<>();

        for (BidSourceRegistration registration : activeRegistrations()) {
            String sourceCode = normalize(registration.getSourceCode());
            ManualBidCollectionSource source = availableSources.get(sourceCode);
            if (source == null || !source.executionEnabled()) {
                results.add(LiveBidCollectionResult.SourceResult.failure(sourceCode, SOURCE_UNAVAILABLE));
                continue;
            }
            try {
                ManualBidCollectionSource.CollectionBatch batch = source.collect(
                        startDate, endDate, allowedIndustryCodes
                );
                results.add(LiveBidCollectionResult.SourceResult.success(sourceCode, batch.candidates()));
            } catch (RuntimeException exception) {
                results.add(LiveBidCollectionResult.SourceResult.failure(sourceCode, COLLECTION_FAILED));
            }
        }

        return new LiveBidCollectionResult(
                status(results), startDate, endDate, results
        );
    }

    private List<BidSourceRegistration> activeRegistrations() {
        return registrationRepository.findAllLatestFirst().stream()
                .filter(BidSourceRegistration::isExecutionEnabled)
                .filter(registration -> registration.getRegistrationStatus()
                        == BidSourceRegistration.RegistrationStatus.APPROVED)
                .filter(registration -> normalize(registration.getSourceCode()) != null)
                .toList();
    }

    private Map<String, ManualBidCollectionSource> availableSources() {
        Map<String, ManualBidCollectionSource> sources = new LinkedHashMap<>();
        for (ManualBidCollectionSource source : sourceRegistry.sources()) {
            String sourceCode = source == null ? null : normalize(source.sourceCode());
            if (sourceCode != null) {
                sources.putIfAbsent(sourceCode, source);
            }
        }
        return sources;
    }

    private LiveBidCollectionResult.Status status(
            List<LiveBidCollectionResult.SourceResult> results
    ) {
        if (results.isEmpty()) {
            return LiveBidCollectionResult.Status.NO_ACTIVE_SOURCES;
        }
        long successes = results.stream()
                .filter(result -> result.status() == LiveBidCollectionResult.SourceStatus.SUCCESS)
                .count();
        if (successes == results.size()) {
            return LiveBidCollectionResult.Status.SUCCESS;
        }
        return successes == 0
                ? LiveBidCollectionResult.Status.FAILED
                : LiveBidCollectionResult.Status.PARTIAL_SUCCESS;
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate are required.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate.");
        }
    }

    private String normalize(String sourceCode) {
        return sourceCode == null || sourceCode.isBlank()
                ? null
                : sourceCode.trim().toUpperCase(Locale.ROOT);
    }
}
