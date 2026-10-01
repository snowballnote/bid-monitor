package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveredBidSourceConfigFactoryTests {

    private static final long SOURCE_ID = 41L;
    private static final Instant REVIEWED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private BidSourceRegistrationRepository registrations;
    private BidSourceDiscoveryResultRepository discoveries;
    private BidSourceDiscoveryReviewRepository reviews;
    private DiscoveredBidSourceConfigFactory factory;

    @BeforeEach
    void setUp() {
        registrations = mock(BidSourceRegistrationRepository.class);
        discoveries = mock(BidSourceDiscoveryResultRepository.class);
        reviews = mock(BidSourceDiscoveryReviewRepository.class);
        factory = new DiscoveredBidSourceConfigFactory(registrations, discoveries, reviews);
        stub(registration("KOGAS"), discovery(BidSourceDiscoveryResult.DiscoveryStatus.READY),
                review(BidSourceDiscoveryReview.ReviewStatus.APPROVED));
    }

    @Test
    void createsImmutableConfigFromReadyApprovedReview() {
        DiscoveredPublicPageSourceConfig config = factory.create(SOURCE_ID).orElseThrow();

        assertEquals(SOURCE_ID, config.sourceId());
        assertEquals(Optional.of("KOGAS"), config.sourceCode());
        assertEquals(URI.create("https://bids.example/notices"), config.listPageUrl());
        assertEquals("https://bids.example/detail?id={value}", config.detailUrlPattern());
        assertEquals("a[href]@href::{key}", config.identifierMapping().expression().orElseThrow());
        assertEquals(REVIEWED_AT, config.reviewUpdatedAt());
    }

    @Test
    void refusesPendingReview() {
        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(
                review(BidSourceDiscoveryReview.ReviewStatus.PENDING_REVIEW)));

        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void refusesRejectedReview() {
        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(
                review(BidSourceDiscoveryReview.ReviewStatus.REJECTED)));

        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void refusesApprovedReviewWhenDiscoveryIsNotReady() {
        when(discoveries.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(
                discovery(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW)));

        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void refusesMissingDiscoveryOrReview() {
        when(discoveries.findBySourceId(SOURCE_ID)).thenReturn(Optional.empty());
        assertTrue(factory.create(SOURCE_ID).isEmpty());

        when(discoveries.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(
                discovery(BidSourceDiscoveryResult.DiscoveryStatus.READY)));
        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.empty());
        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void refusesMissingRequiredMapping() {
        BidSourceDiscoveryReview invalid = review(BidSourceDiscoveryReview.ReviewStatus.APPROVED).toBuilder()
                .identifierMapping(BidSourceDiscoveryReview.UNKNOWN)
                .build();
        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(invalid));

        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void refusesUnsafeUrlsOriginsAndPlaceholders() {
        BidSourceDiscoveryReview approved = review(BidSourceDiscoveryReview.ReviewStatus.APPROVED);

        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(approved.toBuilder()
                .listPageUrl("https://user:secret@bids.example/notices").build()));
        assertTrue(factory.create(SOURCE_ID).isEmpty());

        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(approved.toBuilder()
                .listPageUrl("file:///var/data/notices.html").build()));
        assertTrue(factory.create(SOURCE_ID).isEmpty());

        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(approved.toBuilder()
                .detailUrlPattern("https://other.example/detail?id={value}").build()));
        assertTrue(factory.create(SOURCE_ID).isEmpty());

        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(approved.toBuilder()
                .detailUrlPattern("https://bids.example/detail?id={noticeId}").build()));
        assertTrue(factory.create(SOURCE_ID).isEmpty());

        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(approved.toBuilder()
                .detailUrlPattern("https://bids.example/detail").build()));
        assertTrue(factory.create(SOURCE_ID).isEmpty());
    }

    @Test
    void preservesUnknownOptionalMappingsWithoutConvertingThemToNull() {
        when(registrations.findById(SOURCE_ID)).thenReturn(Optional.of(registration(null)));

        DiscoveredPublicPageSourceConfig config = factory.create(SOURCE_ID).orElseThrow();

        assertTrue(config.sourceCode().isEmpty());
        assertEquals(DiscoveredPublicPageSourceConfig.MappingState.UNKNOWN, config.agencyMapping().state());
        assertTrue(config.agencyMapping().expression().isEmpty());
        assertEquals(DiscoveredPublicPageSourceConfig.MappingState.UNKNOWN, config.paginationMapping().state());
        assertFalse(config.identifierMapping().expression().isEmpty());
    }

    @Test
    void convertsApprovedKogasDiscoveryFixtureWithoutChangingKogasCollector() throws Exception {
        BidSourceSiteStructureAnalyzer analyzer = new BidSourceSiteStructureAnalyzer();
        URI listUri = URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp");
        var analysis = analyzer.analyze(listUri, "text/html; charset=UTF-8",
                Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/list.html")));
        analysis = analyzer.withDetail(analysis, "text/html; charset=EUC-KR",
                Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/detail.html")));
        BidSourceDiscoveryResult discovery = fromAnalysis(analysis);
        BidSourceDiscoveryReview review = review(BidSourceDiscoveryReview.ReviewStatus.APPROVED).toBuilder()
                .listPageUrl(analysis.listPageUrl())
                .detailUrlPattern(analysis.detailUrlPattern())
                .identifierMapping(analysis.identifierMapping())
                .titleMapping(analysis.titleMapping())
                .agencyMapping(known(analysis.agencyMapping()))
                .publishedDateMapping(known(analysis.publishedDateMapping()))
                .deadlineMapping(known(analysis.deadlineMapping()))
                .statusMapping(known(analysis.statusMapping()))
                .attachmentMapping(known(analysis.attachmentMapping()))
                .paginationMapping(known(analysis.paginationMapping()))
                .build();
        stub(registration("KOGAS"), discovery, review);

        DiscoveredPublicPageSourceConfig config = factory.create(SOURCE_ID).orElseThrow();

        assertEquals(Optional.of("KOGAS"), config.sourceCode());
        assertEquals(listUri, config.listPageUrl());
        assertEquals(analysis.detailUrlPattern(), config.detailUrlPattern());
        assertEquals(analysis.identifierMapping(), config.identifierMapping().expression().orElseThrow());
        assertEquals(DiscoveredPublicPageSourceConfig.MappingState.UNKNOWN,
                config.paginationMapping().state());
    }

    private void stub(
            BidSourceRegistration registration,
            BidSourceDiscoveryResult discovery,
            BidSourceDiscoveryReview review
    ) {
        when(registrations.findById(SOURCE_ID)).thenReturn(Optional.of(registration));
        when(discoveries.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(discovery));
        when(reviews.findBySourceId(SOURCE_ID)).thenReturn(Optional.of(review));
    }

    private BidSourceRegistration registration(String sourceCode) {
        return BidSourceRegistration.builder()
                .sourceId(SOURCE_ID)
                .sourceCode(sourceCode)
                .build();
    }

    private BidSourceDiscoveryResult discovery(BidSourceDiscoveryResult.DiscoveryStatus status) {
        return BidSourceDiscoveryResult.builder()
                .sourceId(SOURCE_ID)
                .discoveryStatus(status)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .build();
    }

    private BidSourceDiscoveryReview review(BidSourceDiscoveryReview.ReviewStatus status) {
        return BidSourceDiscoveryReview.builder()
                .sourceId(SOURCE_ID)
                .reviewStatus(status)
                .listPageUrl("https://bids.example/notices")
                .detailUrlPattern("https://bids.example/detail?id={value}")
                .identifierMapping("a[href]@href::{key}")
                .titleMapping("a[href]::text")
                .agencyMapping(BidSourceDiscoveryReview.UNKNOWN)
                .publishedDateMapping(BidSourceDiscoveryReview.UNKNOWN)
                .deadlineMapping(BidSourceDiscoveryReview.UNKNOWN)
                .statusMapping(BidSourceDiscoveryReview.UNKNOWN)
                .attachmentMapping(BidSourceDiscoveryReview.UNKNOWN)
                .paginationMapping(BidSourceDiscoveryReview.UNKNOWN)
                .updatedAt(REVIEWED_AT)
                .build();
    }

    private BidSourceDiscoveryResult fromAnalysis(BidSourceSiteStructureAnalyzer.Analysis analysis) {
        return BidSourceDiscoveryResult.builder()
                .sourceId(SOURCE_ID)
                .discoveryStatus(analysis.status())
                .detectedCollectionMethod(analysis.method())
                .listPageUrl(analysis.listPageUrl())
                .detailUrlPattern(analysis.detailUrlPattern())
                .identifierMapping(analysis.identifierMapping())
                .titleMapping(analysis.titleMapping())
                .agencyMapping(analysis.agencyMapping())
                .publishedDateMapping(analysis.publishedDateMapping())
                .deadlineMapping(analysis.deadlineMapping())
                .statusMapping(analysis.statusMapping())
                .attachmentMapping(analysis.attachmentMapping())
                .paginationMapping(analysis.paginationMapping())
                .identifierConfidence(analysis.identifierConfidence())
                .titleConfidence(analysis.titleConfidence())
                .deadlineConfidence(analysis.deadlineConfidence())
                .attachmentDetected(analysis.attachmentDetected())
                .paginationDetected(analysis.paginationDetected())
                .reasonCodes(analysis.reasonCodes().stream().toList())
                .analyzedAt(REVIEWED_AT)
                .build();
    }

    private String known(String value) {
        return value == null ? BidSourceDiscoveryReview.UNKNOWN : value;
    }
}
