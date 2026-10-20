package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class BidSourceSiteIdentificationService {

    private final BidSourceUrlNormalizer urlNormalizer;
    private final BidSourceCatalogService catalogService;
    private final ManualBidCollectionSourceRegistry sourceRegistry;

    public BidSourceSiteIdentificationService(
            BidSourceUrlNormalizer urlNormalizer,
            BidSourceCatalogService catalogService,
            ManualBidCollectionSourceRegistry sourceRegistry
    ) {
        this.urlNormalizer = urlNormalizer;
        this.catalogService = catalogService;
        this.sourceRegistry = sourceRegistry;
    }

    public Identification identify(String siteUrl) {
        String normalizedUrl = urlNormalizer.normalize(siteUrl);

        return catalogService.fixedSiteUrlsBySourceCode().entrySet().stream()
                .filter(entry -> sourceRegistry.isBuiltInSourceCode(entry.getKey()))
                .filter(entry -> normalizedUrl.equals(urlNormalizer.normalize(entry.getValue())))
                .findFirst()
                .map(entry -> new Identification(Status.SUPPORTED, normalizeSourceCode(entry.getKey())))
                .orElseGet(() -> new Identification(Status.NEW_SITE, null));
    }

    private String normalizeSourceCode(String sourceCode) {
        return sourceCode.trim().toUpperCase(Locale.ROOT);
    }

    public enum Status {
        SUPPORTED,
        NEW_SITE
    }

    public record Identification(Status status, String matchedSourceCode) {
    }
}
