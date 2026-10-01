package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiscoveredBidSourceConfigFactory {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private final BidSourceRegistrationRepository registrationRepository;
    private final BidSourceDiscoveryResultRepository discoveryRepository;
    private final BidSourceDiscoveryReviewRepository reviewRepository;

    public DiscoveredBidSourceConfigFactory(
            BidSourceRegistrationRepository registrationRepository,
            BidSourceDiscoveryResultRepository discoveryRepository,
            BidSourceDiscoveryReviewRepository reviewRepository
    ) {
        this.registrationRepository = registrationRepository;
        this.discoveryRepository = discoveryRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public Optional<DiscoveredPublicPageSourceConfig> create(long sourceId) {
        if (sourceId < 1) return Optional.empty();
        Optional<BidSourceRegistration> registration = registrationRepository.findById(sourceId);
        Optional<BidSourceDiscoveryResult> discovery = discoveryRepository.findBySourceId(sourceId);
        Optional<BidSourceDiscoveryReview> review = reviewRepository.findBySourceId(sourceId);
        if (registration.isEmpty() || discovery.isEmpty() || review.isEmpty()) return Optional.empty();

        BidSourceDiscoveryResult discovered = discovery.get();
        BidSourceDiscoveryReview approved = review.get();
        if (!Long.valueOf(sourceId).equals(registration.get().getSourceId())
                || discovered.getDiscoveryStatus() != BidSourceDiscoveryResult.DiscoveryStatus.READY
                || discovered.getDetectedCollectionMethod() != BidSourceRegistration.CollectionMethod.PUBLIC_PAGE
                || approved.getReviewStatus() != BidSourceDiscoveryReview.ReviewStatus.APPROVED
                || discovered.getSourceId() != sourceId || approved.getSourceId() != sourceId
                || approved.getUpdatedAt() == null) {
            return Optional.empty();
        }

        URI listUri = safeHttpUri(approved.getListPageUrl());
        URI detailSample = safeDetailPattern(approved.getDetailUrlPattern());
        if (listUri == null || detailSample == null || !sameOrigin(listUri, detailSample)) {
            return Optional.empty();
        }

        DiscoveredPublicPageSourceConfig.MappingValue identifier = mapping(approved.getIdentifierMapping());
        DiscoveredPublicPageSourceConfig.MappingValue title = mapping(approved.getTitleMapping());
        if (!configured(identifier) || !configured(title)) return Optional.empty();

        try {
            return Optional.of(new DiscoveredPublicPageSourceConfig(
                    sourceId,
                    Optional.ofNullable(registration.get().getSourceCode()),
                    listUri,
                    approved.getDetailUrlPattern().trim(),
                    identifier,
                    title,
                    mapping(approved.getAgencyMapping()),
                    mapping(approved.getPublishedDateMapping()),
                    mapping(approved.getDeadlineMapping()),
                    mapping(approved.getStatusMapping()),
                    mapping(approved.getAttachmentMapping()),
                    mapping(approved.getPaginationMapping()),
                    approved.getUpdatedAt()
            ));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private DiscoveredPublicPageSourceConfig.MappingValue mapping(String value) {
        if (value == null || value.isBlank() || BidSourceDiscoveryReview.UNKNOWN.equals(value.trim())) {
            return DiscoveredPublicPageSourceConfig.MappingValue.unknown();
        }
        return DiscoveredPublicPageSourceConfig.MappingValue.configured(value);
    }

    private boolean configured(DiscoveredPublicPageSourceConfig.MappingValue mapping) {
        return mapping.state() == DiscoveredPublicPageSourceConfig.MappingState.CONFIGURED;
    }

    private URI safeHttpUri(String value) {
        if (value == null || value.isBlank() || BidSourceDiscoveryReview.UNKNOWN.equals(value.trim())) return null;
        try {
            URI uri = URI.create(value.trim()).normalize();
            return isSafeHttpUri(uri) ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private URI safeDetailPattern(String value) {
        if (value == null || value.isBlank() || BidSourceDiscoveryReview.UNKNOWN.equals(value.trim())) return null;
        String pattern = value.trim();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        boolean found = false;
        StringBuilder sample = new StringBuilder();
        while (matcher.find()) {
            found = true;
            if (!("key".equals(matcher.group(1)) || "value".equals(matcher.group(1)))) return null;
            matcher.appendReplacement(sample, "1");
        }
        matcher.appendTail(sample);
        if (!found || sample.indexOf("{") >= 0 || sample.indexOf("}") >= 0) return null;
        return safeHttpUri(sample.toString());
    }

    private boolean isSafeHttpUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return ("http".equals(scheme) || "https".equals(scheme))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getFragment() == null
                && effectivePort(uri) > 0;
    }

    private boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() > 0) return uri.getPort();
        if (uri.getPort() == 0) return -1;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
