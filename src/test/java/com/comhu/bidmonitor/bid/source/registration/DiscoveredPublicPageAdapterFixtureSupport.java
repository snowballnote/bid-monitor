package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class DiscoveredPublicPageAdapterFixtureSupport {

    private static final long SOURCE_ID = 41L;
    private static final Instant REVIEWED_AT = Instant.parse("2026-10-01T01:00:00Z");

    private DiscoveredPublicPageAdapterFixtureSupport() {
    }

    public static List<BidQualificationDto> collectKogasFixture() throws Exception {
        byte[] list = fixture("list.html");
        byte[] detail = fixture("detail.html");
        DiscoveredPublicPageSourceConfig config = config(list, detail);
        DiscoveredBidSourceConfigFactory factory = mock(DiscoveredBidSourceConfigFactory.class);
        when(factory.create(SOURCE_ID)).thenReturn(Optional.of(config));

        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        BidSourceAvailabilityChecker.Transport transport = (uri, address, limit) -> {
            if (uri.getPath().endsWith("bid_list.jsp")) return html(list);
            if (uri.getRawQuery().contains("notice_code=NC-BAD")) throw new IOException("fixture failure");
            return html(detail);
        };
        BidSourceAvailabilityChecker checker = new BidSourceAvailabilityChecker(
                host -> new InetAddress[]{publicAddress}, transport,
                Clock.fixed(REVIEWED_AT, ZoneOffset.UTC)
        );
        DiscoveredPublicPageCollectionRunner runner = new DiscoveredPublicPageCollectionRunner(
                checker, new DiscoveredPublicPageParser(), 20
        );
        return new DiscoveredPublicPageBidCollector(SOURCE_ID, factory, runner).collect(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)
        );
    }

    private static DiscoveredPublicPageSourceConfig config(byte[] list, byte[] detail) {
        URI listUri = URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp");
        BidSourceSiteStructureAnalyzer analyzer = new BidSourceSiteStructureAnalyzer();
        var analysis = analyzer.analyze(listUri, "text/html; charset=UTF-8", list);
        analysis = analyzer.withDetail(analysis, "text/html; charset=UTF-8", detail);
        return new DiscoveredPublicPageSourceConfig(
                SOURCE_ID, Optional.of("KOGAS"), URI.create(analysis.listPageUrl()),
                analysis.detailUrlPattern(), configured(analysis.identifierMapping()),
                configured(analysis.titleMapping()), mapping(analysis.agencyMapping()),
                mapping(analysis.publishedDateMapping()), mapping(analysis.deadlineMapping()),
                mapping(analysis.statusMapping()), mapping(analysis.attachmentMapping()),
                mapping(analysis.paginationMapping()), REVIEWED_AT
        );
    }

    private static BidSourceAvailabilityChecker.RawResponse html(byte[] body) {
        return new BidSourceAvailabilityChecker.RawResponse(
                200, Map.of("content-type", List.of("text/html; charset=UTF-8")), body
        );
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/fixtures/kogas", name));
    }

    private static DiscoveredPublicPageSourceConfig.MappingValue mapping(String value) {
        return value == null ? unknown() : configured(value);
    }

    private static DiscoveredPublicPageSourceConfig.MappingValue configured(String value) {
        return DiscoveredPublicPageSourceConfig.MappingValue.configured(value);
    }

    private static DiscoveredPublicPageSourceConfig.MappingValue unknown() {
        return DiscoveredPublicPageSourceConfig.MappingValue.unknown();
    }
}
