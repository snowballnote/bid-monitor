package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.G2bApiService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualBidCollectionSourceRegistryTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Test
    void separatesBindingMetadataFromExecutableSourcesWithoutCollecting() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector kogas = collector("KOGAS", false, true);
        BidCandidateCollector expressway = collector("KOREA_EXPRESSWAY", true, false);
        BidCandidateCollector d2b = collector("D2B", false, false);
        when(eligibility.isEligible(kogas)).thenReturn(false);

        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(kogas, expressway, d2b), eligibility
        );
        List<ManualBidCollectionSource> sources = registry.sources();

        assertEquals(java.util.Set.of("KOGAS"), registry.registrationBindingSourceCodes());
        assertEquals(java.util.Set.of("G2B", "KOGAS", "KOREA_EXPRESSWAY", "D2B"),
                registry.fixedSourceCodes());
        assertEquals(List.of("G2B", "KOREA_EXPRESSWAY", "D2B"), sources.stream()
                .map(ManualBidCollectionSource::sourceCode)
                .toList());
        assertFalse(sources.stream()
                .filter(source -> source.sourceCode().equals("D2B"))
                .findFirst().orElseThrow().executionEnabled());
        verify(eligibility).isEligible(kogas);
        verify(kogas, never()).collect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(expressway, never()).collect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(d2b, never()).collect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exposesEligibleKogasAndUsesTheExistingAdditionalSourceReviewPath() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector kogas = collector("KOGAS", true, true);
        BidQualificationDto reviewed = new BidQualificationDto();
        reviewed.setSourceCode("KOGAS");
        when(eligibility.isEligible(kogas)).thenReturn(true);
        when(g2b.getAdditionalBidQualificationList(kogas, START, END, Set.of("6146")))
                .thenReturn(List.of(reviewed));

        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(kogas), eligibility
        );
        ManualBidCollectionSource source = registry.sources().stream()
                .filter(candidate -> candidate.sourceCode().equals("KOGAS"))
                .findFirst()
                .orElseThrow();

        assertFalse(source.usesGenericSchedule());
        assertEquals(List.of(reviewed), source.collect(START, END, Set.of("6146")).candidates());
        verify(eligibility).requireEligible(kogas);
        verify(g2b).getAdditionalBidQualificationList(kogas, START, END, Set.of("6146"));
    }

    @Test
    void exposesEligibleDynamicCollectorAndUsesExistingCompanyReviewPath() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector generic = collector("GENERIC", true, true);
        BidQualificationDto reviewed = new BidQualificationDto();
        reviewed.setSourceCode("GENERIC");
        when(eligibility.isEligible(generic)).thenReturn(true);
        when(g2b.getAdditionalBidQualificationList(generic, START, END, Set.of("6146")))
                .thenReturn(List.of(reviewed));
        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(), eligibility, () -> List.of(generic)
        );

        ManualBidCollectionSource source = registry.sources().stream()
                .filter(candidate -> candidate.sourceCode().equals("GENERIC"))
                .findFirst().orElseThrow();

        org.junit.jupiter.api.Assertions.assertTrue(source.usesGenericSchedule());
        assertEquals(List.of(reviewed), source.collect(START, END, Set.of("6146")).candidates());
        verify(eligibility).requireEligible(generic);
        verify(g2b).getAdditionalBidQualificationList(generic, START, END, Set.of("6146"));
    }

    @Test
    void hidesDynamicCollectorWhenRegistrationActivationIsDisabled() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector generic = collector("GENERIC", true, true);
        when(eligibility.isEligible(generic)).thenReturn(false);
        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(), eligibility, () -> List.of(generic)
        );

        assertEquals(List.of("G2B"), registry.sources().stream()
                .map(ManualBidCollectionSource::sourceCode).toList());
        verify(generic, never()).collect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsFixedCollectorAndExcludesCollidingGenericCollector() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector fixed = collector("KOREA_EXPRESSWAY", true, false);
        BidCandidateCollector generic = collector("KOREA_EXPRESSWAY", true, true);
        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(fixed), eligibility, () -> List.of(generic)
        );

        assertEquals(1, registry.sources().stream()
                .filter(source -> source.sourceCode().equals("KOREA_EXPRESSWAY")).count());
        verify(eligibility, never()).isEligible(generic);
    }

    @Test
    void keepsDedicatedKogasAndExcludesGenericKogasAdapter() {
        G2bApiService g2b = mock(G2bApiService.class);
        BidSourceExecutionEligibilityService eligibility = mock(BidSourceExecutionEligibilityService.class);
        BidCandidateCollector dedicatedKogas = collector("KOGAS", true, true);
        BidCandidateCollector genericKogas = collector("KOGAS", true, true);
        when(eligibility.isEligible(dedicatedKogas)).thenReturn(true);
        ManualBidCollectionSourceRegistry registry = new ManualBidCollectionSourceRegistry(
                g2b, List.of(dedicatedKogas), eligibility, () -> List.of(genericKogas)
        );

        assertEquals(1, registry.sources().stream()
                .filter(source -> source.sourceCode().equals("KOGAS")).count());
        verify(eligibility, times(1)).isEligible(dedicatedKogas);
        verify(eligibility, never()).isEligible(genericKogas);
    }

    private BidCandidateCollector collector(String sourceCode, boolean enabled, boolean bindingSupported) {
        BidCandidateCollector collector = mock(BidCandidateCollector.class);
        when(collector.sourceCode()).thenReturn(sourceCode);
        when(collector.executionEnabled()).thenReturn(enabled);
        when(collector.registrationBindingSupported()).thenReturn(bindingSupported);
        return collector;
    }
}
