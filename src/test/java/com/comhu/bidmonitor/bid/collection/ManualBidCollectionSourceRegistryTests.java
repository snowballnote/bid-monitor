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

        assertEquals(List.of(reviewed), source.collect(START, END, Set.of("6146")).candidates());
        verify(eligibility).requireEligible(kogas);
        verify(g2b).getAdditionalBidQualificationList(kogas, START, END, Set.of("6146"));
    }

    private BidCandidateCollector collector(String sourceCode, boolean enabled, boolean bindingSupported) {
        BidCandidateCollector collector = mock(BidCandidateCollector.class);
        when(collector.sourceCode()).thenReturn(sourceCode);
        when(collector.executionEnabled()).thenReturn(enabled);
        when(collector.registrationBindingSupported()).thenReturn(bindingSupported);
        return collector;
    }
}
