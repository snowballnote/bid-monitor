package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredPublicPageCollectionRunnerTests {

    private static final Instant REVIEWED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(REVIEWED_AT, ZoneOffset.UTC);

    @Test
    void collectsKogasFixtureThroughSecureListDetailAndParserPath() throws Exception {
        byte[] list = fixture("list.html");
        byte[] detail = fixture("detail.html");
        DiscoveredPublicPageSourceConfig config = kogasConfig(list, detail, Optional.of("KOGAS"));
        AtomicInteger detailRequests = new AtomicInteger();
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            if (uri.getPath().endsWith("bid_list.jsp")) return html(list, "UTF-8");
            detailRequests.incrementAndGet();
            if (uri.getRawQuery().contains("notice_code=NC-BAD")) throw new IOException("fixture failure");
            return html(detail, "UTF-8");
        };

        List<BidQualificationDto> candidates = runner(transport, 20).collect(config);

        assertEquals(3, candidates.size());
        assertEquals(List.of(
                "notice_code=NC001&bid_code=BC777&round=2",
                "notice_code=NC-BAD&bid_code=BC-BAD&round=1",
                "notice_code=NC002&bid_code=BC888&round=1"
        ), candidates.stream().map(BidQualificationDto::getSourceNoticeId).toList());
        assertTrue(candidates.getFirst().getDetailUrl().startsWith(
                "https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_detail_view_notice.jsp?"));
        assertFalse(candidates.getFirst().getAttachments().isEmpty());
        assertEquals(3, detailRequests.get());
    }

    @Test
    void rejectsBlockedAndOversizedListResponsesWithSafeCodes() throws Exception {
        DiscoveredPublicPageSourceConfig config = basicConfig(Optional.of("GENERIC"));
        BidSourceAvailabilityChecker blockedChecker = new BidSourceAvailabilityChecker(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")},
                (uri, address, limit) -> html("<html></html>".getBytes(StandardCharsets.UTF_8), "UTF-8"),
                CLOCK
        );
        var blocked = assertThrows(
                DiscoveredPublicPageCollectionRunner.CollectionFailureException.class,
                () -> new DiscoveredPublicPageCollectionRunner(
                        blockedChecker, new DiscoveredPublicPageParser(), 10).collect(config)
        );
        assertEquals("LIST_ADDRESS_BLOCKED", blocked.safeCode());

        BidSourceAvailabilityChecker.Transport oversizedTransport = (uri, address, limit) -> {
            throw new BidSourceAvailabilityChecker.ResponseTooLargeException();
        };
        var oversized = assertThrows(
                DiscoveredPublicPageCollectionRunner.CollectionFailureException.class,
                () -> runner(oversizedTransport, 10).collect(config)
        );
        assertEquals("LIST_RESPONSE_TOO_LARGE", oversized.safeCode());
    }

    @Test
    void blocksPrivateListRedirectBeforeSecondRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            requests.incrementAndGet();
            return new BidSourceAvailabilityChecker.RawResponse(302,
                    Map.of("location", List.of("http://127.0.0.1/private")), new byte[0]);
        };

        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        BidSourceAvailabilityChecker checker = new BidSourceAvailabilityChecker(
                host -> new InetAddress[]{InetAddress.getByName(host.equals("127.0.0.1")
                        ? "127.0.0.1" : publicAddress.getHostAddress())}, transport, CLOCK
        );
        var failure = assertThrows(
                DiscoveredPublicPageCollectionRunner.CollectionFailureException.class,
                () -> new DiscoveredPublicPageCollectionRunner(
                        checker, new DiscoveredPublicPageParser(), 10
                ).collect(basicConfig(Optional.of("GENERIC")))
        );

        assertEquals("LIST_ADDRESS_BLOCKED", failure.safeCode());
        assertEquals(1, requests.get());
    }

    @Test
    void blocksCrossOriginDetailRedirectAndKeepsListCandidate() throws Exception {
        List<URI> requests = new ArrayList<>();
        byte[] list = ("<table><tr><td class='id'>A-1</td>"
                + "<td class='title'>First</td></tr></table>").getBytes(StandardCharsets.UTF_8);
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            requests.add(uri);
            if (uri.getPath().equals("/notices")) return html(list, "UTF-8");
            if (!uri.getHost().equals("bids.example")) throw new AssertionError("cross-origin request");
            return new BidSourceAvailabilityChecker.RawResponse(302,
                    Map.of("location", List.of("https://evil.example/detail?id=A-1")), new byte[0]);
        };

        List<BidQualificationDto> candidates = runner(transport, 10)
                .collect(basicConfig(Optional.of("GENERIC")));

        assertEquals(1, candidates.size());
        assertEquals(List.of("bids.example", "bids.example"),
                requests.stream().map(URI::getHost).toList());
    }

    @Test
    void limitsUniqueDetailFetchesWithoutDroppingCandidates() throws Exception {
        byte[] list = fixture("list.html");
        byte[] detail = fixture("detail.html");
        DiscoveredPublicPageSourceConfig config = kogasConfig(list, detail, Optional.of("KOGAS"));
        AtomicInteger detailRequests = new AtomicInteger();
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            if (uri.getPath().endsWith("bid_list.jsp")) return html(list, "UTF-8");
            detailRequests.incrementAndGet();
            return html(detail, "UTF-8");
        };

        List<BidQualificationDto> candidates = runner(transport, 1).collect(config);

        assertEquals(3, candidates.size());
        assertEquals(1, detailRequests.get());
        assertFalse(candidates.getFirst().getAttachments().isEmpty());
        assertTrue(candidates.get(1).getAttachments().isEmpty());
    }

    @Test
    void rejectsMissingSourceCodeBeforeAnyFetch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            requests.incrementAndGet();
            return html("<html></html>".getBytes(StandardCharsets.UTF_8), "UTF-8");
        };

        var failure = assertThrows(
                DiscoveredPublicPageCollectionRunner.CollectionFailureException.class,
                () -> runner(transport, 10).collect(basicConfig(Optional.empty()))
        );

        assertEquals("SOURCE_CODE_REQUIRED", failure.safeCode());
        assertEquals(0, requests.get());
    }

    private DiscoveredPublicPageCollectionRunner runner(
            BidSourceAvailabilityChecker.Transport transport,
            int maxDetailFetches
    ) throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        BidSourceAvailabilityChecker checker = new BidSourceAvailabilityChecker(
                host -> new InetAddress[]{publicAddress}, transport, CLOCK
        );
        return new DiscoveredPublicPageCollectionRunner(
                checker, new DiscoveredPublicPageParser(), maxDetailFetches
        );
    }

    private BidSourceAvailabilityChecker.RawResponse html(byte[] body, String charset) {
        return new BidSourceAvailabilityChecker.RawResponse(
                200, Map.of("content-type", List.of("text/html; charset=" + charset)), body
        );
    }

    private byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas", name));
    }

    private DiscoveredPublicPageSourceConfig kogasConfig(
            byte[] list,
            byte[] detail,
            Optional<String> sourceCode
    ) {
        URI listUri = URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp");
        BidSourceSiteStructureAnalyzer analyzer = new BidSourceSiteStructureAnalyzer();
        var analysis = analyzer.analyze(listUri, "text/html; charset=UTF-8", list);
        analysis = analyzer.withDetail(analysis, "text/html; charset=UTF-8", detail);
        return new DiscoveredPublicPageSourceConfig(
                41L, sourceCode, URI.create(analysis.listPageUrl()), analysis.detailUrlPattern(),
                configured(analysis.identifierMapping()), configured(analysis.titleMapping()),
                mapping(analysis.agencyMapping()), mapping(analysis.publishedDateMapping()),
                mapping(analysis.deadlineMapping()), mapping(analysis.statusMapping()),
                mapping(analysis.attachmentMapping()), mapping(analysis.paginationMapping()), REVIEWED_AT
        );
    }

    private DiscoveredPublicPageSourceConfig basicConfig(Optional<String> sourceCode) {
        return new DiscoveredPublicPageSourceConfig(
                41L, sourceCode, URI.create("https://bids.example/notices"),
                "https://bids.example/detail?id={value}",
                configured("td.id::text"), configured("td.title::text"),
                unknown(), unknown(), unknown(), unknown(), unknown(), unknown(), REVIEWED_AT
        );
    }

    private DiscoveredPublicPageSourceConfig.MappingValue mapping(String value) {
        return value == null ? unknown() : configured(value);
    }

    private DiscoveredPublicPageSourceConfig.MappingValue configured(String value) {
        return DiscoveredPublicPageSourceConfig.MappingValue.configured(value);
    }

    private DiscoveredPublicPageSourceConfig.MappingValue unknown() {
        return DiscoveredPublicPageSourceConfig.MappingValue.unknown();
    }
}
