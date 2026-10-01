package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidSourceSiteDiscoveryTests {

    private static final URI LIST_URI = URI.create("https://bids.example/notices");
    private static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");
    private static final InetAddress PUBLIC_ADDRESS = address("93.184.216.34");
    private final BidSourceSiteStructureAnalyzer analyzer = new BidSourceSiteStructureAnalyzer();

    @Test
    void detectsKogasFixtureAsReadyWithoutPagination() throws Exception {
        byte[] list = Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/list.html"));
        byte[] detail = Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/detail.html"));

        var analysis = analyzer.analyze(
                URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp"),
                "text/html; charset=UTF-8", list
        );
        analysis = analyzer.withDetail(analysis, "text/html; charset=EUC-KR", detail);

        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.READY, analysis.status());
        assertEquals(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE, analysis.method());
        assertEquals(BidSourceDiscoveryResult.Confidence.HIGH, analysis.identifierConfidence());
        assertEquals(BidSourceDiscoveryResult.Confidence.HIGH, analysis.titleConfidence());
        assertNotNull(analysis.detailUrlPattern());
        assertEquals("a[href]@href::{key}", analysis.identifierMapping());
        assertEquals("a[href]::text", analysis.titleMapping());
        assertTrue(analysis.attachmentDetected());
        assertNotNull(analysis.attachmentMapping());
        assertFalse(analysis.paginationDetected());
        assertTrue(analysis.reasonCodes().contains("PAGINATION_NOT_DETECTED"));
        assertTrue(analysis.reasonCodes().contains("DETAIL_PAGE_ANALYZED"));
    }

    @Test
    void detectsKoreaExpresswayFixtureFieldsButRequiresManualReviewWithoutDetailPath() throws Exception {
        byte[] item = Files.readAllBytes(Path.of(
                "src/test/resources/fixtures/koreaexpressway/list-item.json"));

        var analysis = analyzer.analyze(
                URI.create("https://ebid.ex.co.kr/api/notices"), "application/json", item);

        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW, analysis.status());
        assertEquals(BidSourceRegistration.CollectionMethod.OFFICIAL_API, analysis.method());
        assertEquals(BidSourceDiscoveryResult.Confidence.MEDIUM, analysis.identifierConfidence());
        assertEquals(BidSourceDiscoveryResult.Confidence.MEDIUM, analysis.titleConfidence());
        assertNotNull(analysis.identifierMapping());
        assertNotNull(analysis.titleMapping());
        assertTrue(analysis.reasonCodes().contains("DETAIL_LINK_MISSING"));
        assertTrue(analysis.reasonCodes().contains("INSUFFICIENT_REPEATED_ITEMS"));
    }

    @Test
    void ordinaryHomepageMissingDetailMissingTitleAndSingleItemNeverBecomeReady() {
        assertManual("<html><body><h1>Welcome</h1><nav><a href='/about'>About</a></nav></body></html>");
        assertManual("<table><tr><td>P-1</td><td><a href='/product?id=1'>Product one</a></td></tr>"
                + "<tr><td>P-2</td><td><a href='/product?id=2'>Product two</a></td></tr></table>");
        assertManual("<table><tr><td>N-1</td><td>공고 제목</td></tr><tr><td>N-2</td><td>다른 제목</td></tr></table>");
        assertManual("<table><tr><td>N-1</td><td><a href='/detail?id=1'></a></td></tr>"
                + "<tr><td>N-2</td><td><a href='/detail?id=2'></a></td></tr></table>");
        assertManual("<table><tr><td>N-1</td><td><a href='/detail?id=1'>공고 제목</a></td></tr></table>");
    }

    @Test
    void malformedHtmlIsHandledConservatively() {
        var analysis = analyzer.analyze(LIST_URI, "text/html", bytes(
                "<html><table><tr><td><a href='%%%'>broken<table><script>"));

        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW, analysis.status());
    }

    @Test
    void discoveryBlocksPrivateRedirectAndOversizedResponse() {
        BidSourceDiscoveryResult privateRedirect = service(
                host -> new InetAddress[]{host.equals("bids.example")
                        ? PUBLIC_ADDRESS : address("192.168.1.20")},
                (uri, address, limit) -> new BidSourceAvailabilityChecker.RawResponse(
                        302, Map.of("location", List.of("http://internal.example/admin")), new byte[0]
                )
        ).analyze(1L);
        BidSourceDiscoveryResult oversized = service(
                host -> new InetAddress[]{PUBLIC_ADDRESS},
                (uri, address, limit) -> { throw new BidSourceAvailabilityChecker.ResponseTooLargeException(); }
        ).analyze(1L);

        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.FAILED, privateRedirect.getDiscoveryStatus());
        assertEquals(List.of("FETCH_ADDRESS_BLOCKED"), privateRedirect.getReasonCodes());
        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.FAILED, oversized.getDiscoveryStatus());
        assertEquals(List.of("FETCH_RESPONSE_TOO_LARGE"), oversized.getReasonCodes());
    }

    @Test
    void readyDiscoveryAutomaticallyCreatesPendingReview() throws Exception {
        BidSourceRegistrationRepository registrations = mock(BidSourceRegistrationRepository.class);
        BidSourceDiscoveryResultRepository discoveries = mock(BidSourceDiscoveryResultRepository.class);
        BidSourceAvailabilityChecker availability = mock(BidSourceAvailabilityChecker.class);
        BidSourceDiscoveryReviewService reviews = mock(BidSourceDiscoveryReviewService.class);
        when(registrations.findById(1L)).thenReturn(Optional.of(BidSourceRegistration.builder()
                .sourceId(1L)
                .siteUrl("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp")
                .checkStatus(BidSourceRegistration.CheckStatus.REACHABLE)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .build()));
        when(discoveries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        byte[] list = Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/list.html"));
        byte[] detail = Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas/detail.html"));
        URI listUri = URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp");
        when(availability.fetch(listUri.toString())).thenReturn(
                BidSourceAvailabilityChecker.FetchResult.success(listUri, 200, "text/html; charset=UTF-8", list));
        when(availability.fetch(any())).thenReturn(
                BidSourceAvailabilityChecker.FetchResult.success(listUri, 200, "text/html; charset=EUC-KR", detail));
        when(availability.fetch(listUri.toString())).thenReturn(
                BidSourceAvailabilityChecker.FetchResult.success(listUri, 200, "text/html; charset=UTF-8", list));
        BidSourceDiscoveryService service = new BidSourceDiscoveryService(
                registrations, discoveries, availability, analyzer, reviews,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BidSourceDiscoveryResult result = service.analyze(1L);

        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.READY, result.getDiscoveryStatus());
        verify(reviews).initialize(result);
    }

    private void assertManual(String html) {
        var analysis = analyzer.analyze(LIST_URI, "text/html; charset=UTF-8", bytes(html));
        assertEquals(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW, analysis.status());
    }

    private BidSourceDiscoveryService service(
            BidSourceAvailabilityChecker.HostResolver resolver,
            BidSourceAvailabilityChecker.Transport transport
    ) {
        BidSourceRegistrationRepository registrations = mock(BidSourceRegistrationRepository.class);
        BidSourceDiscoveryResultRepository discoveries = mock(BidSourceDiscoveryResultRepository.class);
        when(registrations.findById(1L)).thenReturn(Optional.of(BidSourceRegistration.builder()
                .sourceId(1L)
                .siteUrl(LIST_URI.toString())
                .checkStatus(BidSourceRegistration.CheckStatus.REACHABLE)
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.PUBLIC_PAGE)
                .build()));
        when(discoveries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new BidSourceDiscoveryService(registrations, discoveries,
                new BidSourceAvailabilityChecker(resolver, transport, clock), analyzer,
                mock(BidSourceDiscoveryReviewService.class), clock);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (java.net.UnknownHostException exception) { throw new IllegalArgumentException(exception); }
    }
}
