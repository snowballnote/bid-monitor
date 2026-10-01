package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.d2b.D2bBidCollector;
import com.comhu.bidmonitor.bid.source.registration.DiscoveredPublicPageBidCollectorProvider;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.service.G2bApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Builds source executors without changing the existing live aggregation API. */
@Component
public class ManualBidCollectionSourceRegistry {

    private final G2bApiService g2bApiService;
    private final List<BidCandidateCollector> additionalCollectors;
    private final BidSourceExecutionEligibilityService executionEligibility;
    private final Supplier<List<BidCandidateCollector>> dynamicCollectorProvider;

    @Autowired
    public ManualBidCollectionSourceRegistry(
            G2bApiService g2bApiService,
            List<BidCandidateCollector> additionalCollectors,
            BidSourceExecutionEligibilityService executionEligibility,
            DiscoveredPublicPageBidCollectorProvider dynamicCollectorProvider
    ) {
        this(g2bApiService, additionalCollectors, executionEligibility, dynamicCollectorProvider::collectors);
    }

    public ManualBidCollectionSourceRegistry(
            G2bApiService g2bApiService,
            List<BidCandidateCollector> additionalCollectors,
            BidSourceExecutionEligibilityService executionEligibility
    ) {
        this(g2bApiService, additionalCollectors, executionEligibility, List::of);
    }

    ManualBidCollectionSourceRegistry(
            G2bApiService g2bApiService,
            List<BidCandidateCollector> additionalCollectors,
            BidSourceExecutionEligibilityService executionEligibility,
            Supplier<List<BidCandidateCollector>> dynamicCollectorProvider
    ) {
        this.g2bApiService = g2bApiService;
        this.additionalCollectors = List.copyOf(additionalCollectors);
        this.executionEligibility = executionEligibility;
        this.dynamicCollectorProvider = dynamicCollectorProvider;
    }

    public List<ManualBidCollectionSource> sources() {
        List<ManualBidCollectionSource> sources = new ArrayList<>();
        sources.add(new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return "G2B";
            }

            @Override
            public CollectionBatch collect(
                    java.time.LocalDate startDate,
                    java.time.LocalDate endDate,
                    java.util.Set<String> allowedLicenseCodes
            ) {
                return CollectionBatch.unmeasured(g2bApiService.getG2bTargetBidQualificationList(
                        startDate, endDate, allowedLicenseCodes
                ));
            }
        });
        Set<String> fixedSourceCodes = new LinkedHashSet<>();
        fixedSourceCodes.add(normalizeSourceCode("G2B"));
        for (BidCandidateCollector collector : additionalCollectors) {
            String sourceCode = collector == null ? null : collector.sourceCode();
            String normalized = normalizeSourceCode(sourceCode);
            if (normalized != null) fixedSourceCodes.add(normalized);
            addEligibleSource(sources, collector, sourceCode);
        }

        Map<String, DynamicCollector> uniqueDynamic = new LinkedHashMap<>();
        Set<String> duplicateDynamicCodes = new LinkedHashSet<>();
        List<BidCandidateCollector> dynamicCollectors = dynamicCollectorProvider.get();
        if (dynamicCollectors != null) {
            for (BidCandidateCollector collector : dynamicCollectors) {
                String sourceCode = collector == null ? null : collector.sourceCode();
                String normalized = normalizeSourceCode(sourceCode);
                if (normalized == null || fixedSourceCodes.contains(normalized)) continue;
                if (uniqueDynamic.putIfAbsent(normalized, new DynamicCollector(collector, sourceCode)) != null) {
                    duplicateDynamicCodes.add(normalized);
                }
            }
        }
        for (Map.Entry<String, DynamicCollector> entry : uniqueDynamic.entrySet()) {
            if (!duplicateDynamicCodes.contains(entry.getKey())) {
                addEligibleSource(sources, entry.getValue().collector(), entry.getValue().sourceCode());
            }
        }
        return List.copyOf(sources);
    }

    private void addEligibleSource(
            List<ManualBidCollectionSource> sources,
            BidCandidateCollector collector,
            String sourceCode
    ) {
        if (collector == null || sourceCode == null || sourceCode.isBlank()) return;
        boolean registrationManaged = collector.registrationBindingSupported();
        if (registrationManaged && !executionEligibility.isEligible(collector)) return;
        sources.add(new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public boolean executionEnabled() {
                return registrationManaged
                        ? executionEligibility.isEligible(collector)
                        : collector.executionEnabled();
            }

            @Override
            public CollectionBatch collect(
                    java.time.LocalDate startDate,
                    java.time.LocalDate endDate,
                    java.util.Set<String> allowedLicenseCodes
            ) {
                if (registrationManaged) {
                    executionEligibility.requireEligible(collector);
                }
                if (collector instanceof D2bBidCollector d2bCollector) {
                    try {
                        D2bBidCollector.CollectionResult measured = d2bCollector.collectMeasured(startDate, endDate);
                        return new CollectionBatch(
                                g2bApiService.processAdditionalBidQualificationList(
                                        collector, measured.candidates(), allowedLicenseCodes
                                ),
                                measured.apiCallCount()
                        );
                    } catch (D2bBidCollector.CollectionException exception) {
                        throw new MeasuredCollectionException(exception.getApiCallCount(), exception);
                    }
                }
                return CollectionBatch.unmeasured(g2bApiService.getAdditionalBidQualificationList(
                        collector, startDate, endDate, allowedLicenseCodes
                ));
            }
        });
    }

    private String normalizeSourceCode(String sourceCode) {
        return sourceCode == null || sourceCode.isBlank()
                ? null : sourceCode.trim().toUpperCase(Locale.ROOT);
    }

    /** Collector 실행 없이 등록 레코드에 연결할 수 있는 명시적 sourceCode 목록을 반환한다. */
    public Set<String> registrationBindingSourceCodes() {
        return additionalCollectors.stream()
                .filter(BidCandidateCollector::registrationBindingSupported)
                .map(BidCandidateCollector::sourceCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private record DynamicCollector(BidCandidateCollector collector, String sourceCode) {
    }
}
