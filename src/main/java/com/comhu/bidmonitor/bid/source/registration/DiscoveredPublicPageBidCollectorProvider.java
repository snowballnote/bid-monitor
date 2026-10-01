package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DiscoveredPublicPageBidCollectorProvider {

    private final BidSourceRegistrationRepository registrationRepository;
    private final DiscoveredBidSourceConfigFactory configFactory;
    private final DiscoveredPublicPageCollectionRunner collectionRunner;

    public DiscoveredPublicPageBidCollectorProvider(
            BidSourceRegistrationRepository registrationRepository,
            DiscoveredBidSourceConfigFactory configFactory,
            DiscoveredPublicPageCollectionRunner collectionRunner
    ) {
        this.registrationRepository = registrationRepository;
        this.configFactory = configFactory;
        this.collectionRunner = collectionRunner;
    }

    public List<BidCandidateCollector> collectors() {
        List<BidCandidateCollector> collectors = new ArrayList<>();
        for (BidSourceRegistration registration : registrationRepository.findAllLatestFirst()) {
            if (registration == null || registration.getSourceId() == null
                    || registration.getSourceId() < 1) {
                continue;
            }
            DiscoveredPublicPageBidCollector collector = new DiscoveredPublicPageBidCollector(
                    registration.getSourceId(), configFactory, collectionRunner
            );
            if (collector.executionEnabled()) collectors.add(collector);
        }
        return List.copyOf(collectors);
    }
}
