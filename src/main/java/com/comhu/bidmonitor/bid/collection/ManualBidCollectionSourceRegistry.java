package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.service.G2bApiService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Builds source executors without changing the existing live aggregation API. */
@Component
public class ManualBidCollectionSourceRegistry {

    private static final String D2B_SOURCE_CODE = "D2B";

    private final G2bApiService g2bApiService;
    private final List<BidCandidateCollector> additionalCollectors;

    public ManualBidCollectionSourceRegistry(
            G2bApiService g2bApiService,
            List<BidCandidateCollector> additionalCollectors
    ) {
        this.g2bApiService = g2bApiService;
        this.additionalCollectors = List.copyOf(additionalCollectors);
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
        for (BidCandidateCollector collector : additionalCollectors) {
            sources.add(new ManualBidCollectionSource() {
                @Override
                public String sourceCode() {
                    return collector.sourceCode();
                }

                @Override
                public boolean executionEnabled() {
                    // D2B stays unavailable until a shared daily-quota reservation is implemented.
                    return !D2B_SOURCE_CODE.equals(collector.sourceCode());
                }

                @Override
                public CollectionBatch collect(
                        java.time.LocalDate startDate,
                        java.time.LocalDate endDate,
                        java.util.Set<String> allowedLicenseCodes
                ) {
                    return CollectionBatch.unmeasured(g2bApiService.getAdditionalBidQualificationList(
                            collector, startDate, endDate, allowedLicenseCodes
                    ));
                }
            });
        }
        return List.copyOf(sources);
    }
}
