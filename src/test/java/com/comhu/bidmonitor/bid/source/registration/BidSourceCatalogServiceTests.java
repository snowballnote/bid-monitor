package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSource;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BidSourceCatalogServiceTests {

    private final BidSourceRegistrationRepository registrations = mock(BidSourceRegistrationRepository.class);
    private final BidSourceDiscoveryResultRepository discoveries = mock(BidSourceDiscoveryResultRepository.class);
    private final BidSourceDiscoveryReviewRepository reviews = mock(BidSourceDiscoveryReviewRepository.class);
    private final BidSourceStateRepository states = mock(BidSourceStateRepository.class);
    private final ManualBidCollectionSourceRegistry registry = mock(ManualBidCollectionSourceRegistry.class);
    private final BidSourceCatalogService service = new BidSourceCatalogService(
            registrations, discoveries, reviews, states, registry
    );

    @Test
    void alwaysIncludesFourFixedSourcesEvenWhenKogasAndD2bAreDisabled() {
        when(registrations.findAllLatestFirst()).thenReturn(List.of());
        when(states.findAll()).thenReturn(List.of(
                BidSourceState.builder()
                        .sourceCode("g2b")
                        .lastSuccessAt(Instant.parse("2026-10-01T01:00:00Z"))
                        .lastFailureAt(Instant.parse("2026-09-30T01:00:00Z"))
                        .build()
        ));
        when(registry.sources()).thenReturn(List.of(
                executable("G2B", true), executable("KOREA_EXPRESSWAY", true), executable("D2B", false)
        ));

        List<BidSourceCatalogItem> catalog = service.catalog();

        assertEquals(List.of("G2B", "KOREA_EXPRESSWAY", "KOGAS", "D2B"),
                catalog.stream().map(BidSourceCatalogItem::sourceCode).toList());
        assertTrue(item(catalog, "G2B").executionEnabled());
        assertFalse(item(catalog, "KOGAS").executionEnabled());
        assertFalse(item(catalog, "D2B").executionEnabled());
        assertEquals(Instant.parse("2026-10-01T01:00:00Z"), item(catalog, "G2B").lastSuccessAt());
        assertEquals(Instant.parse("2026-09-30T01:00:00Z"), item(catalog, "G2B").lastFailureAt());
        assertTrue(catalog.stream().allMatch(source -> source.sourceType()
                == BidSourceCatalogItem.SourceType.FIXED));
    }

    @Test
    void includesBoundAndUnboundDiscoveredSourcesWithRegistrationAndDiscoveryState() {
        BidSourceRegistration unbound = registration(
                11L, null, "신규사이트 A", "https://a.example/bids",
                BidSourceRegistration.RegistrationStatus.PENDING_REVIEW,
                BidSourceRegistration.CollectionMethod.UNDETERMINED,
                BidSourceRegistration.CheckStatus.BLOCKED,
                BidSourceRegistration.SafeFailureCode.ADDRESS_BLOCKED
        );
        BidSourceRegistration bound = registration(
                12L, "custom_b", "신규사이트 B", "https://b.example/bids",
                BidSourceRegistration.RegistrationStatus.APPROVED,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CheckStatus.REACHABLE,
                null
        );
        when(registrations.findAllLatestFirst()).thenReturn(List.of(unbound, bound));
        when(states.findAll()).thenReturn(List.of());
        when(registry.sources()).thenReturn(List.of(executable("CUSTOM_B", true)));
        when(discoveries.findBySourceId(11L)).thenReturn(Optional.of(BidSourceDiscoveryResult.builder()
                .sourceId(11L)
                .discoveryStatus(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW)
                .build()));
        when(reviews.findBySourceId(11L)).thenReturn(Optional.of(BidSourceDiscoveryReview.builder()
                .sourceId(11L)
                .reviewStatus(BidSourceDiscoveryReview.ReviewStatus.PENDING_REVIEW)
                .build()));

        List<BidSourceCatalogItem> catalog = service.catalog();
        BidSourceCatalogItem unboundItem = catalog.stream()
                .filter(source -> Long.valueOf(11L).equals(source.sourceId())).findFirst().orElseThrow();
        BidSourceCatalogItem boundItem = item(catalog, "CUSTOM_B");

        assertNull(unboundItem.sourceCode());
        assertEquals(BidSourceCatalogItem.SourceType.DISCOVERED, unboundItem.sourceType());
        assertEquals("PENDING_REVIEW", unboundItem.registrationStatus());
        assertEquals("BLOCKED", unboundItem.checkStatus());
        assertEquals("MANUAL_REVIEW", unboundItem.discoveryStatus());
        assertEquals("PENDING_REVIEW", unboundItem.reviewStatus());
        assertEquals("ADDRESS_BLOCKED", unboundItem.safeFailureCode());
        assertFalse(unboundItem.executionEnabled());
        assertEquals("신규사이트 B", boundItem.sourceName());
        assertTrue(boundItem.executionEnabled());
    }

    @Test
    void mergesFixedAndDiscoveredSourceCodesCaseInsensitivelyWithoutOverwritingFixedIdentity() {
        BidSourceRegistration newest = registration(
                21L, "kogas", "등록된 가스공사", "https://registered.example/kogas",
                BidSourceRegistration.RegistrationStatus.APPROVED,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CheckStatus.REACHABLE,
                null
        );
        BidSourceRegistration older = registration(
                20L, "KOGAS", "이전 가스공사", "https://older.example/kogas",
                BidSourceRegistration.RegistrationStatus.UNDER_REVIEW,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                BidSourceRegistration.CheckStatus.REACHABLE,
                null
        );
        when(registrations.findAllLatestFirst()).thenReturn(List.of(newest, older));
        when(states.findAll()).thenReturn(List.of());
        when(registry.sources()).thenReturn(List.of(executable("KOGAS", true)));

        List<BidSourceCatalogItem> catalog = service.catalog();

        assertEquals(1, catalog.stream().filter(source -> "KOGAS".equals(source.sourceCode())).count());
        BidSourceCatalogItem kogas = item(catalog, "KOGAS");
        assertEquals(21L, kogas.sourceId());
        assertEquals("한국가스공사", kogas.sourceName());
        assertEquals("https://bid.kogas.or.kr:9443/", kogas.siteUrl());
        assertEquals(BidSourceCatalogItem.SourceType.FIXED, kogas.sourceType());
        assertEquals("APPROVED", kogas.registrationStatus());
        assertTrue(kogas.executionEnabled());
    }

    private BidSourceCatalogItem item(List<BidSourceCatalogItem> catalog, String sourceCode) {
        return catalog.stream().filter(source -> sourceCode.equals(source.sourceCode())).findFirst().orElseThrow();
    }

    private ManualBidCollectionSource executable(String sourceCode, boolean enabled) {
        return new ManualBidCollectionSource() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public boolean executionEnabled() {
                return enabled;
            }

            @Override
            public CollectionBatch collect(
                    java.time.LocalDate startDate,
                    java.time.LocalDate endDate,
                    java.util.Set<String> allowedLicenseCodes
            ) {
                return CollectionBatch.unmeasured(List.of());
            }
        };
    }

    private BidSourceRegistration registration(
            long sourceId,
            String sourceCode,
            String sourceName,
            String siteUrl,
            BidSourceRegistration.RegistrationStatus registrationStatus,
            BidSourceRegistration.CollectionMethod collectionMethod,
            BidSourceRegistration.CheckStatus checkStatus,
            BidSourceRegistration.SafeFailureCode failureCode
    ) {
        return BidSourceRegistration.builder()
                .sourceId(sourceId)
                .sourceCode(sourceCode)
                .sourceName(sourceName)
                .siteUrl(siteUrl)
                .registrationStatus(registrationStatus)
                .collectionMethod(collectionMethod)
                .executionEnabled(false)
                .checkStatus(checkStatus)
                .detectedCollectionMethod(collectionMethod)
                .safeFailureCode(failureCode)
                .createdAt(Instant.parse("2026-10-01T01:00:00Z"))
                .updatedAt(Instant.parse("2026-10-01T01:00:00Z"))
                .build();
    }
}
