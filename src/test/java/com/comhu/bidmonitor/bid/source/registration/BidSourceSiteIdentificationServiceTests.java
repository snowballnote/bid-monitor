package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class BidSourceSiteIdentificationServiceTests {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "G2B", "KOGAS", "KOREA_EXPRESSWAY", "D2B", "LH", "KWATER"
    );

    private final BidSourceCatalogService catalogService = mock(BidSourceCatalogService.class);
    private final ManualBidCollectionSourceRegistry sourceRegistry =
            mock(ManualBidCollectionSourceRegistry.class);
    private final BidSourceSiteIdentificationService service = new BidSourceSiteIdentificationService(
            new BidSourceUrlNormalizer(), catalogService, sourceRegistry
    );

    @BeforeEach
    void configureSupportedCatalog() {
        when(sourceRegistry.isBuiltInSourceCode(anyString()))
                .thenAnswer(invocation -> SUPPORTED_CODES.contains(invocation.getArgument(0)));
        when(catalogService.fixedSiteUrlsBySourceCode()).thenReturn(Map.of(
                "G2B", "https://www.g2b.go.kr/",
                "KOREA_EXPRESSWAY", "https://ebid.ex.co.kr/",
                "KOGAS", "https://bid.kogas.or.kr:9443/",
                "D2B", "https://www.d2b.go.kr/",
                "LH", "https://ebid.lh.or.kr/",
                "KWATER", "https://ebid.kwater.or.kr/",
                "CUSTOM_SITE", "https://custom.example/",
                "KORAIL", "https://ebid.korail.com/"
        ));
    }

    @Test
    void identifiesOnlyTheSixSupportedFixedSites() {
        assertSupported("HTTPS://WWW.G2B.GO.KR:443", "G2B");
        assertSupported("https://ebid.ex.co.kr", "KOREA_EXPRESSWAY");
        assertSupported("https://BID.KOGAS.OR.KR:9443/", "KOGAS");
        assertSupported("https://www.d2b.go.kr/", "D2B");
        assertSupported("https://ebid.lh.or.kr", "LH");
        assertSupported("https://ebid.kwater.or.kr/", "KWATER");
    }

    @Test
    void doesNotMatchLookalikeHostsPortsPathsOrDiscoveredCatalogEntries() {
        assertNewSite("https://www.g2b.go.kr.example.com/");
        assertNewSite("https://bid.kogas.or.kr/");
        assertNewSite("https://www.g2b.go.kr/notices");
        assertNewSite("https://custom.example/");
        assertNewSite("https://public.example/bids");
    }

    @Test
    void keepsKorailUnsupportedUntilItIsPresentInTheOperatingRegistry() {
        assertNewSite("https://ebid.korail.com/");
    }

    private void assertSupported(String siteUrl, String sourceCode) {
        BidSourceSiteIdentificationService.Identification identification = service.identify(siteUrl);
        assertEquals(BidSourceSiteIdentificationService.Status.SUPPORTED, identification.status());
        assertEquals(sourceCode, identification.matchedSourceCode());
    }

    private void assertNewSite(String siteUrl) {
        BidSourceSiteIdentificationService.Identification identification = service.identify(siteUrl);
        assertEquals(BidSourceSiteIdentificationService.Status.NEW_SITE, identification.status());
        assertNull(identification.matchedSourceCode());
    }

}
