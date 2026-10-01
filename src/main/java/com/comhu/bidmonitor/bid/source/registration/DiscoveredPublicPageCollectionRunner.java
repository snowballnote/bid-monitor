package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiscoveredPublicPageCollectionRunner {

    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9._-]+)"
    );

    private final BidSourceAvailabilityChecker availabilityChecker;
    private final DiscoveredPublicPageParser parser;
    private final int maxDetailFetches;

    @Autowired
    public DiscoveredPublicPageCollectionRunner(
            BidSourceAvailabilityChecker availabilityChecker,
            @Value("${bid-source.discovery.max-detail-fetches:20}") int maxDetailFetches
    ) {
        this(availabilityChecker, new DiscoveredPublicPageParser(), maxDetailFetches);
    }

    DiscoveredPublicPageCollectionRunner(
            BidSourceAvailabilityChecker availabilityChecker,
            DiscoveredPublicPageParser parser,
            int maxDetailFetches
    ) {
        this.availabilityChecker = Objects.requireNonNull(availabilityChecker, "availabilityChecker is required.");
        this.parser = Objects.requireNonNull(parser, "parser is required.");
        if (maxDetailFetches < 0) throw new IllegalArgumentException("maxDetailFetches cannot be negative.");
        this.maxDetailFetches = maxDetailFetches;
    }

    public List<BidQualificationDto> collect(DiscoveredPublicPageSourceConfig config) {
        if (config == null) throw failure("INVALID_CONFIG");
        if (config.sourceCode().isEmpty()) throw failure("SOURCE_CODE_REQUIRED");

        BidSourceAvailabilityChecker.FetchResult listResult = availabilityChecker.fetch(
                config.listPageUrl().toASCIIString()
        );
        if (listResult.failureCode() != null) {
            throw failure("LIST_" + listResult.failureCode().name());
        }
        if (!isHtml(listResult.contentType())) throw failure("LIST_UNSUPPORTED_CONTENT_TYPE");

        String listHtml;
        try {
            listHtml = decode(listResult.body(), listResult.contentType());
        } catch (RuntimeException exception) {
            throw failure("LIST_INVALID_CHARSET");
        }

        Map<URI, Optional<String>> detailCache = new HashMap<>();
        int[] attemptedDetails = {0};
        return parser.parse(config, listHtml, detailUri -> detailCache.computeIfAbsent(detailUri, uri -> {
            if (attemptedDetails[0] >= maxDetailFetches || !sameOrigin(config.listPageUrl(), uri)) {
                return Optional.empty();
            }
            attemptedDetails[0]++;
            BidSourceAvailabilityChecker.FetchResult detailResult = availabilityChecker.fetch(
                    uri.toASCIIString(), redirected -> sameOrigin(config.listPageUrl(), redirected)
            );
            if (detailResult.failureCode() != null || !isHtml(detailResult.contentType())) {
                return Optional.empty();
            }
            try {
                return Optional.of(decode(detailResult.body(), detailResult.contentType()));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }));
    }

    private boolean isHtml(String contentType) {
        if (contentType == null) return false;
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "text/html".equals(mediaType) || "application/xhtml+xml".equals(mediaType);
    }

    private String decode(byte[] body, String contentType) {
        Matcher headerCharset = CHARSET.matcher(contentType == null ? "" : contentType);
        if (headerCharset.find()) return new String(body, Charset.forName(headerCharset.group(1)));

        String prefix = new String(body, 0, Math.min(body.length, 8192), StandardCharsets.ISO_8859_1);
        Matcher htmlCharset = CHARSET.matcher(prefix);
        Charset charset = htmlCharset.find() ? Charset.forName(htmlCharset.group(1)) : StandardCharsets.UTF_8;
        return new String(body, charset);
    }

    private boolean sameOrigin(URI origin, URI target) {
        if (target == null || target.getScheme() == null || target.getHost() == null
                || target.getRawUserInfo() != null || target.getFragment() != null) {
            return false;
        }
        String scheme = target.getScheme().toLowerCase(Locale.ROOT);
        return ("http".equals(scheme) || "https".equals(scheme))
                && origin.getScheme().equalsIgnoreCase(target.getScheme())
                && origin.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(origin) == effectivePort(target);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() > 0) return uri.getPort();
        if (uri.getPort() == 0) return -1;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private CollectionFailureException failure(String safeCode) {
        return new CollectionFailureException(safeCode);
    }

    public static final class CollectionFailureException extends RuntimeException {
        private final String safeCode;

        private CollectionFailureException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
