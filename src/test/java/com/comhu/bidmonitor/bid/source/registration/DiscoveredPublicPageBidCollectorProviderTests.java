package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveredPublicPageBidCollectorProviderTests {

    private static final long SOURCE_ID = 41L;
    private final BidSourceRegistrationRepository registrations = mock(BidSourceRegistrationRepository.class);
    private final DiscoveredBidSourceConfigFactory factory = mock(DiscoveredBidSourceConfigFactory.class);
    private final DiscoveredPublicPageCollectionRunner runner = mock(DiscoveredPublicPageCollectionRunner.class);
    private final DiscoveredPublicPageBidCollectorProvider provider =
            new DiscoveredPublicPageBidCollectorProvider(registrations, factory, runner);

    @Test
    void rebuildsCollectorsForEachRegistryLookupSoReviewChangesNeedNoRestart() {
        when(registrations.findAllLatestFirst()).thenReturn(List.of(registration()));
        when(factory.create(SOURCE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(config(Optional.of("GENERIC"))));

        assertTrue(provider.collectors().isEmpty());
        List<BidCandidateCollector> refreshed = provider.collectors();

        assertEquals(1, refreshed.size());
        assertEquals("GENERIC", refreshed.getFirst().sourceCode());
        assertTrue(refreshed.getFirst() instanceof DiscoveredPublicPageBidCollector);
        verify(registrations, times(2)).findAllLatestFirst();
    }

    @Test
    void excludesMissingInvalidOrSourceCodeLessFactoryResults() {
        BidSourceRegistration second = BidSourceRegistration.builder().sourceId(42L).build();
        when(registrations.findAllLatestFirst()).thenReturn(List.of(registration(), second));
        when(factory.create(SOURCE_ID)).thenReturn(Optional.empty());
        when(factory.create(42L)).thenReturn(Optional.of(config(42L, Optional.empty())));

        assertTrue(provider.collectors().isEmpty());
    }

    private BidSourceRegistration registration() {
        return BidSourceRegistration.builder().sourceId(SOURCE_ID).build();
    }

    private DiscoveredPublicPageSourceConfig config(Optional<String> sourceCode) {
        return config(SOURCE_ID, sourceCode);
    }

    private DiscoveredPublicPageSourceConfig config(long sourceId, Optional<String> sourceCode) {
        return new DiscoveredPublicPageSourceConfig(
                sourceId, sourceCode, URI.create("https://bids.example/notices"),
                "https://bids.example/detail?id={value}",
                DiscoveredPublicPageSourceConfig.MappingValue.configured("td.id::text"),
                DiscoveredPublicPageSourceConfig.MappingValue.configured("td.title::text"),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                DiscoveredPublicPageSourceConfig.MappingValue.unknown(),
                Instant.parse("2026-10-01T01:00:00Z")
        );
    }
}
