package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
class BidSourceSiteStructureAnalyzer {

    private static final Pattern DATE = Pattern.compile("(?:20\\d{2})[-./]?(?:0?[1-9]|1[0-2])[-./]?(?:0?[1-9]|[12]\\d|3[01])");
    private static final Pattern UUID_OR_NUMBER = Pattern.compile("(?i)([0-9a-f]{8}-[0-9a-f-]{27,}|\\d{2,})");
    private final ObjectMapper objectMapper;

    BidSourceSiteStructureAnalyzer() {
        this.objectMapper = new ObjectMapper();
    }

    Analysis analyze(URI uri, String contentType, byte[] body) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        try {
            if (type.contains("json") || firstNonWhitespace(body) == '{' || firstNonWhitespace(body) == '[') {
                return analyzeJson(uri, body);
            }
            if (type.contains("html") || firstNonWhitespace(body) == '<') {
                return analyzeHtml(uri, contentType, body);
            }
            return Analysis.unsupported(uri.toString());
        } catch (RuntimeException | java.io.IOException exception) {
            return Analysis.manual(uri.toString(), BidSourceRegistration.CollectionMethod.UNDETERMINED,
                    Set.of("MALFORMED_OR_UNRECOGNIZED_CONTENT"));
        }
    }

    private Analysis analyzeHtml(URI uri, String contentType, byte[] body) {
        Document document = Jsoup.parse(new String(body, charset(contentType)), uri.toString());
        String structuralText = document.title() + " " + document.select("table, thead, th, a[href]").text()
                + " " + document.select("table[id], table[class], a[href]").toString();
        String normalizedStructure = structuralText.toLowerCase(Locale.ROOT);
        boolean bidSemantics = List.of("공고", "입찰", "bid", "tender", "notice", "procurement")
                .stream().anyMatch(normalizedStructure::contains);
        List<Element> rows = document.select("tr:has(a[href]), li:has(a[href]), article:has(a[href])");
        Map<String, List<LinkCandidate>> byPattern = new LinkedHashMap<>();
        for (Element row : rows) {
            for (Element link : row.select("a[href]")) {
                URI target = resolveSameOrigin(uri, link.absUrl("href"));
                if (target == null || link.text().isBlank()) {
                    continue;
                }
                String pattern = urlPattern(target);
                byPattern.computeIfAbsent(pattern, ignored -> new ArrayList<>())
                        .add(new LinkCandidate(target, link.text().trim(), row.text()));
            }
        }
        Map.Entry<String, List<LinkCandidate>> repeated = byPattern.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .orElse(null);
        List<LinkCandidate> candidates = repeated == null ? List.of() : repeated.getValue();
        Set<String> reasons = new LinkedHashSet<>();
        reasons.add(bidSemantics ? "BID_SEMANTICS_DETECTED" : "BID_SEMANTICS_MISSING");
        if (candidates.size() >= 2) reasons.add("REPEATED_STRUCTURE_DETECTED");
        else reasons.add("INSUFFICIENT_REPEATED_ITEMS");

        BidSourceDiscoveryResult.Confidence title = candidates.stream().filter(c -> c.title().length() >= 4).count() >= 2
                ? BidSourceDiscoveryResult.Confidence.HIGH : BidSourceDiscoveryResult.Confidence.NONE;
        if (title == BidSourceDiscoveryResult.Confidence.NONE) reasons.add("TITLE_MISSING");
        else reasons.add("TITLE_DETECTED");

        BidSourceDiscoveryResult.Confidence identifier = identifierConfidence(candidates);
        if (identifier == BidSourceDiscoveryResult.Confidence.NONE) reasons.add("IDENTIFIER_MISSING");
        else reasons.add("IDENTIFIER_DETECTED");

        BidSourceDiscoveryResult.Confidence deadline = candidates.stream()
                .filter(candidate -> DATE.matcher(candidate.rowText()).find()).count() >= 2
                ? BidSourceDiscoveryResult.Confidence.MEDIUM : BidSourceDiscoveryResult.Confidence.NONE;
        if (deadline != BidSourceDiscoveryResult.Confidence.NONE) reasons.add("DEADLINE_OR_DATE_DETECTED");

        boolean pagination = !document.select(
                "a[href*=page], a[href*=Page], input[name*=page], input[name*=Page], select[name*=page]"
        ).isEmpty();
        reasons.add(pagination ? "PAGINATION_DETECTED" : "PAGINATION_NOT_DETECTED");

        boolean agencyDetected = hasHeader(document, "기관", "organization", "agency");
        boolean publishedDateDetected = hasHeader(document, "게시", "공고일", "published", "date");
        boolean statusDetected = hasHeader(document, "상태", "status");
        if (agencyDetected) reasons.add("ORDERING_ORGANIZATION_DETECTED");
        if (publishedDateDetected) reasons.add("PUBLISHED_DATE_DETECTED");
        if (statusDetected) reasons.add("NOTICE_STATUS_DETECTED");

        String detailPattern = repeated == null ? null : repeated.getKey();
        URI detailCandidate = candidates.isEmpty() ? null : candidates.getFirst().uri();
        if (detailPattern == null) reasons.add("DETAIL_LINK_MISSING");
        else reasons.add("DETAIL_PATTERN_DETECTED");

        boolean ready = bidSemantics && candidates.size() >= 2 && detailPattern != null
                && identifier.ordinal() >= BidSourceDiscoveryResult.Confidence.MEDIUM.ordinal()
                && title.ordinal() >= BidSourceDiscoveryResult.Confidence.MEDIUM.ordinal();
        return new Analysis(
                ready ? BidSourceDiscoveryResult.DiscoveryStatus.READY
                        : BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW,
                BidSourceRegistration.CollectionMethod.PUBLIC_PAGE,
                uri.toString(), detailPattern, detailCandidate,
                identifier == BidSourceDiscoveryResult.Confidence.NONE ? null : "a[href]@href::{key}",
                title == BidSourceDiscoveryResult.Confidence.NONE ? null : "a[href]::text",
                agencyDetected ? "table::column(agency)" : null,
                publishedDateDetected ? "table::column(publishedDate)" : null,
                deadline == BidSourceDiscoveryResult.Confidence.NONE ? null : "table::column(deadline)",
                statusDetected ? "table::column(status)" : null,
                null,
                pagination ? "a[href*=page],input[name*=page],select[name*=page]" : null,
                identifier, title, deadline, false, pagination, reasons
        );
    }

    private Analysis analyzeJson(URI uri, byte[] body) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(body);
        List<JsonNode> items = largestArray(root);
        if (items.isEmpty() && root != null && root.isObject()) {
            items = List.of(root);
        }
        long identifiers = items.stream().filter(this::hasIdentifier).count();
        long titles = items.stream().filter(this::hasTitle).count();
        String identifierMapping = items.stream().map(item -> fieldName(item, "id", "no", "number"))
                .filter(value -> value != null).findFirst().orElse(null);
        String titleMapping = items.stream().map(item -> fieldName(item, "title", "name", "nm"))
                .filter(value -> value != null).findFirst().orElse(null);
        String detail = items.stream().map(this::detailUrl).filter(value -> value != null).findFirst().orElse(null);
        Set<String> reasons = new LinkedHashSet<>();
        if (items.size() >= 2) reasons.add("REPEATED_STRUCTURE_DETECTED");
        else reasons.add("INSUFFICIENT_REPEATED_ITEMS");
        if (identifiers >= Math.min(2, items.size())) reasons.add("IDENTIFIER_DETECTED");
        else reasons.add("IDENTIFIER_MISSING");
        if (titles >= Math.min(2, items.size())) reasons.add("TITLE_DETECTED");
        else reasons.add("TITLE_MISSING");
        if (detail == null) reasons.add("DETAIL_LINK_MISSING");
        reasons.add("PAGINATION_NOT_DETECTED");
        return new Analysis(
                BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW,
                BidSourceRegistration.CollectionMethod.OFFICIAL_API,
                uri.toString(), detail, detail == null ? null : resolveSameOrigin(uri, detail),
                identifierMapping, titleMapping, null, null, null, null, null, null,
                identifiers > 0 ? BidSourceDiscoveryResult.Confidence.MEDIUM : BidSourceDiscoveryResult.Confidence.NONE,
                titles > 0 ? BidSourceDiscoveryResult.Confidence.MEDIUM : BidSourceDiscoveryResult.Confidence.NONE,
                BidSourceDiscoveryResult.Confidence.NONE,
                false, false, reasons
        );
    }

    Analysis withDetail(Analysis analysis, String contentType, byte[] body) {
        if (analysis.detailCandidate() == null) return analysis;
        try {
            Document detail = Jsoup.parse(new String(body, charset(contentType)), analysis.detailCandidate().toString());
            if (detail.text().strip().length() < 20) {
                return analysis.withDetailFailure("DETAIL_PAGE_UNRECOGNIZED");
            }
            boolean attachment = detail.select("a[href]").stream().anyMatch(link -> {
                String href = link.attr("href").toLowerCase(Locale.ROOT);
                return href.matches(".*\\.(pdf|hwp|hwpx|docx?|xlsx?|zip)(?:[?#].*)?$")
                        || href.contains("download") || href.contains("attach");
            });
            Set<String> reasons = new LinkedHashSet<>(analysis.reasonCodes());
            reasons.add("DETAIL_PAGE_ANALYZED");
            if (attachment) reasons.add("ATTACHMENT_DETECTED");
            return analysis.withDetailMetadata(attachment,
                    attachment ? "a[href*=download],a[href*=attach],a[href$=.pdf]" : null,
                    reasons);
        } catch (RuntimeException exception) {
            Set<String> reasons = new LinkedHashSet<>(analysis.reasonCodes());
            reasons.add("DETAIL_PAGE_UNRECOGNIZED");
            return analysis.withDetailMetadata(false, null, reasons);
        }
    }

    private BidSourceDiscoveryResult.Confidence identifierConfidence(List<LinkCandidate> candidates) {
        if (candidates.size() < 2) return BidSourceDiscoveryResult.Confidence.NONE;
        boolean stableQueryKey = candidates.stream().allMatch(candidate -> candidate.uri().getRawQuery() != null);
        boolean varyingTarget = candidates.stream().map(candidate -> candidate.uri().toString()).distinct().count() >= 2;
        return stableQueryKey && varyingTarget
                ? BidSourceDiscoveryResult.Confidence.HIGH
                : varyingTarget ? BidSourceDiscoveryResult.Confidence.MEDIUM
                : BidSourceDiscoveryResult.Confidence.NONE;
    }

    private URI resolveSameOrigin(URI base, String target) {
        try {
            if (target == null || target.isBlank()) return null;
            URI resolved = base.resolve(target);
            int basePort = effectivePort(base);
            return base.getScheme().equalsIgnoreCase(resolved.getScheme())
                    && base.getHost().equalsIgnoreCase(resolved.getHost())
                    && basePort == effectivePort(resolved) ? resolved : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String urlPattern(URI uri) {
        String path = UUID_OR_NUMBER.matcher(uri.getPath()).replaceAll("{key}");
        if (uri.getRawQuery() == null) return uri.getScheme() + "://" + uri.getAuthority() + path;
        List<String> keys = Pattern.compile("&").splitAsStream(uri.getRawQuery())
                .map(part -> part.contains("=") ? part.substring(0, part.indexOf('=')) : part)
                .sorted().map(key -> key + "={value}").toList();
        return uri.getScheme() + "://" + uri.getAuthority() + path + "?" + String.join("&", keys);
    }

    private boolean hasHeader(Document document, String... labels) {
        String headers = document.select("th").text().toLowerCase(Locale.ROOT);
        for (String label : labels) if (headers.contains(label)) return true;
        return false;
    }

    private List<JsonNode> largestArray(JsonNode node) {
        if (node == null) return List.of();
        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            node.forEach(values::add);
            return values;
        }
        List<JsonNode> largest = List.of();
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                List<JsonNode> candidate = largestArray(child);
                if (candidate.size() > largest.size()) largest = candidate;
            }
        }
        return largest;
    }

    private boolean hasIdentifier(JsonNode item) {
        return fields(item).entrySet().stream().anyMatch(entry ->
                (entry.getKey().contains("id") || entry.getKey().contains("no") || entry.getKey().contains("number"))
                        && entry.getValue().isValueNode() && !entry.getValue().asText().isBlank());
    }

    private boolean hasTitle(JsonNode item) {
        return fields(item).entrySet().stream().anyMatch(entry ->
                (entry.getKey().contains("title") || entry.getKey().contains("name") || entry.getKey().endsWith("nm"))
                        && entry.getValue().isTextual() && entry.getValue().asText().length() >= 4);
    }

    private String detailUrl(JsonNode item) {
        return fields(item).entrySet().stream()
                .filter(entry -> entry.getKey().contains("url") || entry.getKey().contains("link"))
                .map(entry -> entry.getValue().asText(null))
                .filter(value -> value != null && (value.startsWith("http://") || value.startsWith("https://")))
                .findFirst().orElse(null);
    }

    private String fieldName(JsonNode item, String... candidates) {
        return fields(item).keySet().stream().filter(key -> {
            for (String candidate : candidates) {
                if (key.contains(candidate) || key.endsWith(candidate)) return true;
            }
            return false;
        }).findFirst().orElse(null);
    }

    private Map<String, JsonNode> fields(JsonNode item) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (item != null && item.isObject()) {
            item.fields().forEachRemaining(entry -> fields.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        }
        return fields;
    }

    private Charset charset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String value = part.trim();
                if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try { return Charset.forName(value.substring(8).trim()); }
                    catch (RuntimeException ignored) { return StandardCharsets.UTF_8; }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private char firstNonWhitespace(byte[] body) {
        for (byte value : body) {
            char character = (char) Byte.toUnsignedInt(value);
            if (!Character.isWhitespace(character)) return character;
        }
        return 0;
    }

    private record LinkCandidate(URI uri, String title, String rowText) { }

    record Analysis(
            BidSourceDiscoveryResult.DiscoveryStatus status,
            BidSourceRegistration.CollectionMethod method,
            String listPageUrl,
            String detailUrlPattern,
            URI detailCandidate,
            String identifierMapping,
            String titleMapping,
            String agencyMapping,
            String publishedDateMapping,
            String deadlineMapping,
            String statusMapping,
            String attachmentMapping,
            String paginationMapping,
            BidSourceDiscoveryResult.Confidence identifierConfidence,
            BidSourceDiscoveryResult.Confidence titleConfidence,
            BidSourceDiscoveryResult.Confidence deadlineConfidence,
            boolean attachmentDetected,
            boolean paginationDetected,
            Set<String> reasonCodes
    ) {
        static Analysis unsupported(String url) {
            return new Analysis(BidSourceDiscoveryResult.DiscoveryStatus.UNSUPPORTED,
                    BidSourceRegistration.CollectionMethod.UNDETERMINED, url, null, null,
                    null, null, null, null, null, null, null, null,
                    BidSourceDiscoveryResult.Confidence.NONE, BidSourceDiscoveryResult.Confidence.NONE,
                    BidSourceDiscoveryResult.Confidence.NONE, false, false, Set.of("UNSUPPORTED_CONTENT"));
        }

        static Analysis manual(String url, BidSourceRegistration.CollectionMethod method, Set<String> reasons) {
            return new Analysis(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW, method, url, null, null,
                    null, null, null, null, null, null, null, null,
                    BidSourceDiscoveryResult.Confidence.NONE, BidSourceDiscoveryResult.Confidence.NONE,
                    BidSourceDiscoveryResult.Confidence.NONE, false, false, reasons);
        }

        Analysis withDetailMetadata(boolean attachment, String newAttachmentMapping, Set<String> reasons) {
            return new Analysis(status, method, listPageUrl, detailUrlPattern, detailCandidate,
                    identifierMapping, titleMapping, agencyMapping, publishedDateMapping, deadlineMapping,
                    statusMapping, newAttachmentMapping, paginationMapping,
                    identifierConfidence, titleConfidence, deadlineConfidence,
                    attachment, paginationDetected, Set.copyOf(reasons));
        }

        Analysis withDetailFailure(String reason) {
            Set<String> reasons = new LinkedHashSet<>(reasonCodes);
            reasons.add(reason);
            return new Analysis(BidSourceDiscoveryResult.DiscoveryStatus.MANUAL_REVIEW,
                    method, listPageUrl, detailUrlPattern, detailCandidate,
                    identifierMapping, titleMapping, agencyMapping, publishedDateMapping, deadlineMapping,
                    statusMapping, attachmentMapping, paginationMapping,
                    identifierConfidence, titleConfidence, deadlineConfidence,
                    false, paginationDetected, Set.copyOf(reasons));
        }
    }
}
