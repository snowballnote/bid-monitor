package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSource;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BidSourceCatalogService {

    private static final List<FixedSource> FIXED_SOURCES = List.of(
            new FixedSource("G2B", "나라장터", "https://www.g2b.go.kr/", "OFFICIAL_API"),
            new FixedSource("KOREA_EXPRESSWAY", "한국도로공사", "https://ebid.ex.co.kr/", "PUBLIC_PAGE"),
            new FixedSource("KOGAS", "한국가스공사", "https://bid.kogas.or.kr:9443/", "PUBLIC_PAGE"),
            new FixedSource("D2B", "D2B", "https://www.d2b.go.kr/", "OFFICIAL_API")
    );

    private final BidSourceRegistrationRepository registrations;
    private final BidSourceDiscoveryResultRepository discoveries;
    private final BidSourceDiscoveryReviewRepository reviews;
    private final BidSourceStateRepository states;
    private final ManualBidCollectionSourceRegistry sourceRegistry;

    public BidSourceCatalogService(
            BidSourceRegistrationRepository registrations,
            BidSourceDiscoveryResultRepository discoveries,
            BidSourceDiscoveryReviewRepository reviews,
            BidSourceStateRepository states,
            ManualBidCollectionSourceRegistry sourceRegistry
    ) {
        this.registrations = registrations;
        this.discoveries = discoveries;
        this.reviews = reviews;
        this.states = states;
        this.sourceRegistry = sourceRegistry;
    }

    public List<BidSourceCatalogItem> catalog() {
        Map<String, Boolean> executableByCode = executableByCode();
        Map<String, BidSourceState> stateByCode = stateByCode();
        Map<String, CatalogEntry> entries = new LinkedHashMap<>();

        for (FixedSource source : FIXED_SOURCES) {
            entries.put(source.sourceCode(), CatalogEntry.fixed(source));
        }
        for (BidSourceRegistration registration : registrations.findAllLatestFirst()) {
            String normalizedCode = normalize(registration.getSourceCode());
            String key = normalizedCode == null
                    ? "REGISTRATION:" + registration.getSourceId()
                    : normalizedCode;
            CatalogEntry current = entries.get(key);
            if (current == null) {
                entries.put(key, CatalogEntry.discovered(registration, normalizedCode));
            } else if (current.registration() == null) {
                entries.put(key, current.withRegistration(registration));
            }
        }

        return entries.values().stream()
                .map(entry -> toItem(entry, executableByCode, stateByCode))
                .toList();
    }

    private BidSourceCatalogItem toItem(
            CatalogEntry entry,
            Map<String, Boolean> executableByCode,
            Map<String, BidSourceState> stateByCode
    ) {
        BidSourceRegistration registration = entry.registration();
        Long sourceId = registration == null ? null : registration.getSourceId();
        BidSourceDiscoveryResult discovery = sourceId == null
                ? null : discoveries.findBySourceId(sourceId).orElse(null);
        BidSourceDiscoveryReview review = sourceId == null
                ? null : reviews.findBySourceId(sourceId).orElse(null);
        BidSourceState state = entry.sourceCode() == null
                ? null : stateByCode.get(entry.sourceCode());

        return new BidSourceCatalogItem(
                sourceId,
                entry.sourceCode(),
                entry.sourceName(),
                entry.siteUrl(),
                entry.sourceType(),
                entry.collectionMethod(),
                entry.sourceCode() != null && executableByCode.getOrDefault(entry.sourceCode(), false),
                registration == null ? null : name(registration.getRegistrationStatus()),
                registration == null ? null : name(registration.getCheckStatus()),
                discovery == null ? null : name(discovery.getDiscoveryStatus()),
                review == null ? null : name(review.getReviewStatus()),
                state == null ? null : state.getLastSuccessAt(),
                state == null ? null : state.getLastFailureAt(),
                registration == null ? null : name(registration.getSafeFailureCode())
        );
    }

    private Map<String, Boolean> executableByCode() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ManualBidCollectionSource source : sourceRegistry.sources()) {
            String sourceCode = normalize(source.sourceCode());
            if (sourceCode != null) {
                result.merge(sourceCode, source.executionEnabled(), Boolean::logicalOr);
            }
        }
        return result;
    }

    private Map<String, BidSourceState> stateByCode() {
        Map<String, BidSourceState> result = new LinkedHashMap<>();
        for (BidSourceState state : states.findAll()) {
            String sourceCode = normalize(state.getSourceCode());
            if (sourceCode != null) result.putIfAbsent(sourceCode, state);
        }
        return result;
    }

    private String normalize(String sourceCode) {
        return sourceCode == null || sourceCode.isBlank()
                ? null : sourceCode.trim().toUpperCase(Locale.ROOT);
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private record FixedSource(
            String sourceCode,
            String sourceName,
            String siteUrl,
            String collectionMethod
    ) {
    }

    private record CatalogEntry(
            String sourceCode,
            String sourceName,
            String siteUrl,
            BidSourceCatalogItem.SourceType sourceType,
            String collectionMethod,
            BidSourceRegistration registration
    ) {
        private static CatalogEntry fixed(FixedSource source) {
            return new CatalogEntry(
                    source.sourceCode(), source.sourceName(), source.siteUrl(),
                    BidSourceCatalogItem.SourceType.FIXED, source.collectionMethod(), null
            );
        }

        private static CatalogEntry discovered(BidSourceRegistration registration, String sourceCode) {
            return new CatalogEntry(
                    sourceCode, registration.getSourceName(), registration.getSiteUrl(),
                    BidSourceCatalogItem.SourceType.DISCOVERED,
                    registration.getCollectionMethod() == null ? null : registration.getCollectionMethod().name(),
                    registration
            );
        }

        private CatalogEntry withRegistration(BidSourceRegistration registration) {
            return new CatalogEntry(
                    sourceCode, sourceName, siteUrl, sourceType, collectionMethod, registration
            );
        }
    }
}
