package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredPublicPageParserTests {

    private static final Instant REVIEWED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private final DiscoveredPublicPageParser parser = new DiscoveredPublicPageParser();

    @Test
    void parsesApprovedKogasConfigAndFixturesWithoutSiteSpecificRules() throws Exception {
        URI listUri = URI.create("https://bid.kogas.or.kr:9443/supplier/contents/bid/bid_list.jsp");
        String listHtml = Files.readString(Path.of("src/test/resources/fixtures/kogas/list.html"));
        String detailHtml = Files.readString(Path.of("src/test/resources/fixtures/kogas/detail.html"));
        BidSourceSiteStructureAnalyzer analyzer = new BidSourceSiteStructureAnalyzer();
        var analysis = analyzer.analyze(listUri, "text/html; charset=UTF-8",
                listHtml.getBytes(StandardCharsets.UTF_8));
        analysis = analyzer.withDetail(analysis, "text/html; charset=EUC-KR",
                detailHtml.getBytes(StandardCharsets.UTF_8));

        List<BidQualificationDto> candidates = parser.parse(
                kogasConfig(analysis),
                listHtml,
                uri -> uri.getRawQuery() != null && uri.getRawQuery().contains("notice_code=NC001")
                        ? Optional.of(detailHtml) : Optional.empty()
        );

        assertEquals(3, candidates.size());
        BidQualificationDto first = candidates.getFirst();
        assertEquals("KOGAS", first.getSourceCode());
        assertEquals("notice_code=NC001&bid_code=BC777&round=2", first.getSourceNoticeId());
        assertEquals("천연가스 정보시스템 감리용역", first.getBidNtceNm());
        assertEquals("https://bid.kogas.or.kr:9443/supplier/contents/bid/"
                + "bid_detail_view_notice.jsp?notice_code=NC001&bid_code=BC777&round=2", first.getDetailUrl());
        assertEquals("디지털혁신처", first.getNtceInsttNm());
        assertEquals("2026.09.10", first.getBidNtceDt());
        assertEquals("2026.09.30 17:00", first.getBidClseDt());
        assertEquals("공고중", first.getNoticeStatus());
        assertEquals(1, first.getAttachments().size());
        assertEquals("감리용역_공고문.hwp", first.getAttachments().getFirst().getFileName());
    }

    @Test
    void keepsUnknownOptionalMappingsEmpty() {
        String html = "<table><tr><td class='id'>A-1</td><td class='title'>First notice</td></tr></table>";

        BidQualificationDto candidate = parser.parse(basicConfig(), html, uri -> Optional.empty()).getFirst();

        assertNull(candidate.getNtceInsttNm());
        assertNull(candidate.getBidNtceDt());
        assertNull(candidate.getBidClseDt());
        assertNull(candidate.getNoticeStatus());
        assertTrue(candidate.getAttachments().isEmpty());
    }

    @Test
    void replacesDetailPlaceholderOnlyWithMappedIdentifier() {
        String html = "<table><tr><td class='id'>A 1</td><td class='title'>First notice</td></tr></table>";

        BidQualificationDto candidate = parser.parse(basicConfig(), html, uri -> Optional.empty()).getFirst();

        assertEquals("A 1", candidate.getSourceNoticeId());
        assertEquals("https://bids.example/detail?id=A%201", candidate.getDetailUrl());
    }

    @Test
    void refusesUnsafeGeneratedDetailUrl() {
        String html = "<table><tr><td class='id'>A-1</td><td class='title'>First notice</td></tr></table>";

        for (String pattern : List.of(
                "https://evil.example/detail?id={value}",
                "http://bids.example/detail?id={value}",
                "https://user:secret@bids.example/detail?id={value}",
                "https://bids.example/detail?id={value}#fragment"
        )) {
            DiscoveredPublicPageSourceConfig unsafe = config(
                    URI.create("https://bids.example/notices"), pattern,
                    configured("td.id::text"), configured("td.title::text")
            );
            assertTrue(parser.parse(unsafe, html, uri -> Optional.empty()).isEmpty());
        }
    }

    @Test
    void deduplicatesIdentifiersAndIsolatesMissingOrMalformedRows() {
        String html = """
                <table>
                  <tr><td class='id'>A-1</td><td class='title'>First notice</td></tr>
                  <tr><td class='id'>A-1</td><td class='title'>Duplicate notice</td></tr>
                  <tr><td class='id'></td><td class='title'>Missing identifier</td></tr>
                  <tr><td class='id'>BROKEN</td><td class='title'></td></tr>
                  <tr><td class='id'>B-2</td><td class='title'>Second notice</td></tr>
                </table>
                """;

        List<BidQualificationDto> candidates = parser.parse(basicConfig(), html, uri -> Optional.empty());

        assertEquals(List.of("A-1", "B-2"), candidates.stream()
                .map(BidQualificationDto::getSourceNoticeId).toList());
        assertEquals("First notice", candidates.getFirst().getBidNtceNm());
    }

    @Test
    void appliesConfiguredDetailSelectorsWithoutGuessingUnknownFields() {
        DiscoveredPublicPageSourceConfig config = new DiscoveredPublicPageSourceConfig(
                41L, Optional.of("GENERIC"), URI.create("https://bids.example/notices"),
                "https://bids.example/detail?id={value}",
                configured("td.id::text"), configured("td.title::text"),
                configured("#agency::text"), configured("#published::text"),
                unknown(), configured("#status::text"), unknown(), unknown(), REVIEWED_AT
        );
        String list = "<table><tr><td class='id'>A-1</td><td class='title'>First notice</td></tr></table>";
        String detail = "<div id='agency'>Example Agency</div>"
                + "<time id='published'>2026-10-01</time><span id='status'>OPEN</span>";

        BidQualificationDto candidate = parser.parse(config, list, uri -> Optional.of(detail)).getFirst();

        assertEquals("Example Agency", candidate.getNtceInsttNm());
        assertEquals("2026-10-01", candidate.getBidNtceDt());
        assertEquals("OPEN", candidate.getNoticeStatus());
        assertNull(candidate.getBidClseDt());
    }

    private DiscoveredPublicPageSourceConfig kogasConfig(BidSourceSiteStructureAnalyzer.Analysis analysis) {
        return new DiscoveredPublicPageSourceConfig(
                41L, Optional.of("KOGAS"), URI.create(analysis.listPageUrl()), analysis.detailUrlPattern(),
                configured(analysis.identifierMapping()), configured(analysis.titleMapping()),
                mapping(analysis.agencyMapping()), mapping(analysis.publishedDateMapping()),
                mapping(analysis.deadlineMapping()), mapping(analysis.statusMapping()),
                mapping(analysis.attachmentMapping()), mapping(analysis.paginationMapping()), REVIEWED_AT
        );
    }

    private DiscoveredPublicPageSourceConfig basicConfig() {
        return config(
                URI.create("https://bids.example/notices"),
                "https://bids.example/detail?id={value}",
                configured("td.id::text"), configured("td.title::text")
        );
    }

    private DiscoveredPublicPageSourceConfig config(
            URI listPageUrl,
            String detailPattern,
            DiscoveredPublicPageSourceConfig.MappingValue identifier,
            DiscoveredPublicPageSourceConfig.MappingValue title
    ) {
        return new DiscoveredPublicPageSourceConfig(
                41L, Optional.empty(), listPageUrl, detailPattern, identifier, title,
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
