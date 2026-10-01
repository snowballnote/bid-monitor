package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveredPublicPageBidCollectorTests {

    private static final long SOURCE_ID = 41L;
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private final DiscoveredBidSourceConfigFactory factory = mock(DiscoveredBidSourceConfigFactory.class);
    private final DiscoveredPublicPageCollectionRunner runner = mock(DiscoveredPublicPageCollectionRunner.class);
    private final DiscoveredPublicPageBidCollector collector =
            new DiscoveredPublicPageBidCollector(SOURCE_ID, factory, runner);

    @Test
    void executesReadyApprovedConfigWithItsSourceCodeAndOneSnapshot() {
        DiscoveredPublicPageSourceConfig config = config(Optional.of("GENERIC"));
        List<BidQualificationDto> expected = List.of(candidate("A-1", "First notice"));
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config));
        when(runner.collect(config)).thenReturn(expected);

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(expected, result);
        assertEquals(SOURCE_ID, collector.sourceId());
        assertTrue(collector.registrationBindingSupported());
        verify(factory).create(SOURCE_ID);
        verify(runner).collect(config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "PENDING_REVIEW", "REJECTED"})
    void refusesWhenFactoryHasNoApprovedExecutableConfig(String ignoredReviewState) {
        when(factory.create(SOURCE_ID)).thenReturn(Optional.empty());

        var failure = assertThrows(
                DiscoveredPublicPageBidCollector.CollectionException.class,
                () -> collector.collect(START, END)
        );

        assertEquals("CONFIG_NOT_EXECUTABLE", failure.safeCode());
        assertFalse(collector.executionEnabled());
        assertNull(collector.sourceCode());
        verify(runner, never()).collect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesConfigWithoutSourceCode() {
        DiscoveredPublicPageSourceConfig config = config(Optional.empty());
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config));

        var failure = assertThrows(
                DiscoveredPublicPageBidCollector.CollectionException.class,
                () -> collector.collect(START, END)
        );

        assertEquals("SOURCE_CODE_REQUIRED", failure.safeCode());
        assertFalse(collector.executionEnabled());
        assertNull(collector.sourceCode());
        verify(runner, never()).collect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exposesTechnicalExecutionReadinessWithoutChangingRegistrationActivation() {
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config(Optional.of("KOGAS"))));

        assertTrue(collector.executionEnabled());
        assertEquals("KOGAS", collector.sourceCode());
    }

    @Test
    void convertsRunnerListFailureToCollectorSafeFailure() {
        DiscoveredPublicPageSourceConfig config = config(Optional.of("GENERIC"));
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config));
        when(runner.collect(config)).thenThrow(
                new DiscoveredPublicPageCollectionRunner.CollectionFailureException("LIST_HTTP_ERROR")
        );

        var failure = assertThrows(
                DiscoveredPublicPageBidCollector.CollectionException.class,
                () -> collector.collect(START, END)
        );

        assertEquals("LIST_HTTP_ERROR", failure.safeCode());
        assertEquals("LIST_HTTP_ERROR", failure.getMessage());
    }

    @Test
    void preservesCandidatesReturnedAfterAnIsolatedDetailFailure() {
        DiscoveredPublicPageSourceConfig config = config(Optional.of("GENERIC"));
        List<BidQualificationDto> partial = List.of(
                candidate("A-1", "Enriched"), candidate("B-2", "List only")
        );
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config));
        when(runner.collect(config)).thenReturn(partial);

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(List.of("A-1", "B-2"), result.stream()
                .map(BidQualificationDto::getSourceNoticeId).toList());
    }

    private DiscoveredPublicPageSourceConfig config(Optional<String> sourceCode) {
        return new DiscoveredPublicPageSourceConfig(
                SOURCE_ID, sourceCode, URI.create("https://bids.example/notices"),
                "https://bids.example/detail?id={value}",
                configured("td.id::text"), configured("td.title::text"),
                unknown(), unknown(), unknown(), unknown(), unknown(), unknown(),
                Instant.parse("2026-10-01T01:00:00Z")
        );
    }

    private BidQualificationDto candidate(String identifier, String title) {
        BidQualificationDto candidate = new BidQualificationDto();
        candidate.setSourceCode("GENERIC");
        candidate.setSourceNoticeId(identifier);
        candidate.setBidNtceNm(title);
        return candidate;
    }

    private DiscoveredPublicPageSourceConfig.MappingValue configured(String value) {
        return DiscoveredPublicPageSourceConfig.MappingValue.configured(value);
    }

    private DiscoveredPublicPageSourceConfig.MappingValue unknown() {
        return DiscoveredPublicPageSourceConfig.MappingValue.unknown();
    }
}
