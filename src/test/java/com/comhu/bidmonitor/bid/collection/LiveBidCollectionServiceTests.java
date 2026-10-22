package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveBidCollectionServiceTests {

    private static final LocalDate START = LocalDate.of(2026, 10, 22);
    private static final LocalDate END = LocalDate.of(2026, 10, 22);

    @Mock
    private ManualBidCollectionSourceRegistry sourceRegistry;

    @Mock
    private BidSourceRegistrationRepository registrationRepository;

    @Mock
    private AllowedIndustryCodeProvider allowedIndustryCodeProvider;

    @Mock
    private ManualBidCollectionSource activeSource;

    @Mock
    private ManualBidCollectionSource inactiveSource;

    private LiveBidCollectionService service;

    @BeforeEach
    void setUp() {
        service = new LiveBidCollectionService(
                sourceRegistry, registrationRepository, allowedIndustryCodeProvider
        );
        lenient().when(allowedIndustryCodeProvider.currentCodes()).thenReturn(Set.of("6146", "6525"));
    }

    @Test
    void returnsFilteredCollectorOutputWithoutUsingNoticePersistence() {
        BidQualificationDto notice = new BidQualificationDto();
        notice.setSourceCode("KOGAS");
        notice.setSourceNoticeId("2026-001");
        notice.setBidNtceNm("정보시스템 감리 용역");
        when(activeSource.sourceCode()).thenReturn("KOGAS");
        when(activeSource.executionEnabled()).thenReturn(true);
        when(activeSource.collect(START, END, Set.of("6146", "6525")))
                .thenReturn(ManualBidCollectionSource.CollectionBatch.unmeasured(List.of(notice)));
        when(inactiveSource.sourceCode()).thenReturn("D2B");
        when(sourceRegistry.sources()).thenReturn(List.of(activeSource, inactiveSource));
        when(registrationRepository.findAllLatestFirst()).thenReturn(List.of(
                registration("KOGAS", true),
                registration("D2B", false)
        ));

        LiveBidCollectionResult result = service.collect(START, END);

        assertThat(result.status()).isEqualTo(LiveBidCollectionResult.Status.SUCCESS);
        assertThat(result.notices()).containsExactly(notice);
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceCode()).isEqualTo("KOGAS");
            assertThat(source.status()).isEqualTo(LiveBidCollectionResult.SourceStatus.SUCCESS);
        });
        verify(activeSource).collect(START, END, Set.of("6146", "6525"));
        verify(inactiveSource, never()).collect(START, END, Set.of("6146", "6525"));
    }

    @Test
    void returnsNoActiveSourcesWithoutCallingCollectors() {
        when(sourceRegistry.sources()).thenReturn(List.of(activeSource));
        when(activeSource.sourceCode()).thenReturn("KOGAS");
        when(registrationRepository.findAllLatestFirst())
                .thenReturn(List.of(registration("KOGAS", false)));

        LiveBidCollectionResult result = service.collect(START, END);

        assertThat(result.status()).isEqualTo(LiveBidCollectionResult.Status.NO_ACTIVE_SOURCES);
        assertThat(result.sources()).isEmpty();
        assertThat(result.notices()).isEmpty();
        verify(activeSource, never()).collect(START, END, Set.of("6146", "6525"));
    }

    @Test
    void reportsSafeSourceFailureWithoutReturningPartialNoticeData() {
        when(activeSource.sourceCode()).thenReturn("KOGAS");
        when(activeSource.executionEnabled()).thenReturn(true);
        when(activeSource.collect(START, END, Set.of("6146", "6525")))
                .thenThrow(new IllegalStateException("sensitive upstream response"));
        when(sourceRegistry.sources()).thenReturn(List.of(activeSource));
        when(registrationRepository.findAllLatestFirst())
                .thenReturn(List.of(registration("KOGAS", true)));

        LiveBidCollectionResult result = service.collect(START, END);

        assertThat(result.status()).isEqualTo(LiveBidCollectionResult.Status.FAILED);
        assertThat(result.notices()).isEmpty();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.errorCode()).isEqualTo("COLLECTION_FAILED");
            assertThat(source.notices()).isEmpty();
        });
    }

    @Test
    void rejectsInvalidDateRangeBeforeReadingSources() {
        assertThatThrownBy(() -> service.collect(END, START.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startDate must not be after endDate.");

        verify(sourceRegistry, never()).sources();
        verify(registrationRepository, never()).findAllLatestFirst();
    }

    private BidSourceRegistration registration(String sourceCode, boolean enabled) {
        return BidSourceRegistration.builder()
                .sourceId(enabled ? 1L : 2L)
                .sourceName(sourceCode)
                .siteUrl("https://example.test/")
                .sourceCode(sourceCode)
                .registrationStatus(BidSourceRegistration.RegistrationStatus.APPROVED)
                .collectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .executionEnabled(enabled)
                .checkStatus(BidSourceRegistration.CheckStatus.REACHABLE)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .build();
    }
}
