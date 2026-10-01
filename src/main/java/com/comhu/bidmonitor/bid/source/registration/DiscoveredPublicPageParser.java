package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscoveredPublicPageParser {

    private static final Pattern COLUMN_MAPPING = Pattern.compile("table::column\\((\\d+)\\)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(key|value)}");

    public List<BidQualificationDto> parse(
            DiscoveredPublicPageSourceConfig config,
            String listHtml,
            DetailHtmlProvider detailProvider
    ) {
        Objects.requireNonNull(config, "config is required.");
        Objects.requireNonNull(listHtml, "listHtml is required.");
        Objects.requireNonNull(detailProvider, "detailProvider is required.");
        Document list = Jsoup.parse(listHtml, config.listPageUrl().toASCIIString());
        LinkedHashMap<String, BidQualificationDto> candidates = new LinkedHashMap<>();
        for (Element row : candidateRows(list, config.identifierMapping())) {
            try {
                ParsedIdentifier identifier = identifier(config, row);
                String title = value(row, expression(config.titleMapping()));
                if (identifier == null || title == null) continue;
                String sourceIdentity = config.sourceCode().orElse("sourceId:" + config.sourceId());
                String candidateKey = sourceIdentity + "\u0000" + identifier.value();
                if (candidates.containsKey(candidateKey)) continue;
                BidQualificationDto candidate = candidate(config, identifier, title, row);
                enrichFromDetail(config, candidate, identifier.detailUri(), detailProvider);
                candidates.put(candidateKey, candidate);
            } catch (RuntimeException ignored) {
                // A malformed row is isolated; other rows remain parseable.
            }
        }
        return List.copyOf(candidates.values());
    }

    public List<BidQualificationDto> parse(
            DiscoveredPublicPageSourceConfig config,
            String listHtml,
            Map<URI, String> detailHtmlByUri
    ) {
        Map<URI, String> snapshot = Map.copyOf(detailHtmlByUri);
        return parse(config, listHtml, uri -> Optional.ofNullable(snapshot.get(uri)));
    }

    private List<Element> candidateRows(
            Document document,
            DiscoveredPublicPageSourceConfig.MappingValue identifierMapping
    ) {
        String mapping = expression(identifierMapping);
        String selector = selector(mapping.endsWith("::{key}")
                ? mapping.substring(0, mapping.length() - "::{key}".length()) : mapping);
        Set<Element> rows = new LinkedHashSet<>();
        for (Element matched : document.select(selector)) {
            Element row = matched.closest("tr,li,article");
            if (row != null) rows.add(row);
        }
        return List.copyOf(rows);
    }

    private ParsedIdentifier identifier(DiscoveredPublicPageSourceConfig config, Element row) {
        String mapping = expression(config.identifierMapping());
        if (mapping.endsWith("::{key}")) {
            String targetValue = value(row, mapping.substring(0, mapping.length() - "::{key}".length()));
            URI target = safeResolve(config.listPageUrl(), targetValue);
            TemplateMatch match = match(config.detailUrlPattern(), target);
            if (target == null || match == null) return null;
            URI generated = generatedDetailUri(config, match.values(), null);
            if (generated == null || !generated.equals(target)) return null;
            String identity = target.getRawQuery() == null ? target.getRawPath() : target.getRawQuery();
            return clean(identity) == null ? null : new ParsedIdentifier(identity, generated);
        }

        String identity = value(row, mapping);
        if (identity == null) return null;
        URI generated = generatedDetailUri(config, List.of(), identity);
        return generated == null ? null : new ParsedIdentifier(identity, generated);
    }

    private BidQualificationDto candidate(
            DiscoveredPublicPageSourceConfig config,
            ParsedIdentifier identifier,
            String title,
            Element row
    ) {
        BidQualificationDto candidate = new BidQualificationDto();
        candidate.setSourceCode(config.sourceCode().orElse(null));
        candidate.setSourceNoticeId(identifier.value());
        candidate.setBidNtceNo(identifier.value());
        candidate.setBidNtceNm(title);
        candidate.setDetailUrl(identifier.detailUri().toASCIIString());
        candidate.setBidNtceDtlUrl(identifier.detailUri().toASCIIString());
        candidate.setNtceInsttNm(optionalValue(row, config.agencyMapping()));
        candidate.setBidNtceDt(optionalValue(row, config.publishedDateMapping()));
        candidate.setBidClseDt(optionalValue(row, config.deadlineMapping()));
        candidate.setNoticeStatus(optionalValue(row, config.statusMapping()));
        candidate.setAttachments(List.of());
        return candidate;
    }

    private void enrichFromDetail(
            DiscoveredPublicPageSourceConfig config,
            BidQualificationDto candidate,
            URI detailUri,
            DetailHtmlProvider detailProvider
    ) {
        Optional<String> html;
        try {
            html = detailProvider.find(detailUri);
        } catch (RuntimeException exception) {
            return;
        }
        if (html == null || html.isEmpty()) return;
        try {
            Document detail = Jsoup.parse(html.get(), detailUri.toASCIIString());
            candidate.setNtceInsttNm(firstNonNull(
                    optionalValue(detail, config.agencyMapping()), candidate.getNtceInsttNm()));
            candidate.setBidNtceDt(firstNonNull(
                    optionalValue(detail, config.publishedDateMapping()), candidate.getBidNtceDt()));
            candidate.setBidClseDt(firstNonNull(
                    optionalValue(detail, config.deadlineMapping()), candidate.getBidClseDt()));
            candidate.setNoticeStatus(firstNonNull(
                    optionalValue(detail, config.statusMapping()), candidate.getNoticeStatus()));
            candidate.setAttachments(attachments(config, detail, detailUri));
        } catch (RuntimeException ignored) {
            // Detail failures keep the valid list candidate intact.
        }
    }

    private List<BidAttachmentDto> attachments(
            DiscoveredPublicPageSourceConfig config,
            Document detail,
            URI detailUri
    ) {
        if (config.attachmentMapping().state() == DiscoveredPublicPageSourceConfig.MappingState.UNKNOWN) {
            return List.of();
        }
        String selector = expression(config.attachmentMapping());
        LinkedHashMap<String, BidAttachmentDto> attachments = new LinkedHashMap<>();
        for (Element element : detail.select(selector)) {
            String href = element.hasAttr("href") ? element.attr("href") : null;
            URI uri = safeResolve(detailUri, href);
            if (!safeForConfig(config, uri)) continue;
            String fileName = fileName(element);
            attachments.putIfAbsent(uri.toASCIIString(),
                    new BidAttachmentDto(fileName, uri.toASCIIString(), null, "NOT_ANALYZED"));
        }
        return List.copyOf(attachments.values());
    }

    private String optionalValue(Element scope, DiscoveredPublicPageSourceConfig.MappingValue mapping) {
        if (mapping.state() == DiscoveredPublicPageSourceConfig.MappingState.UNKNOWN) return null;
        return value(scope, expression(mapping));
    }

    private String value(Element scope, String mapping) {
        Matcher column = COLUMN_MAPPING.matcher(mapping);
        if (column.matches()) {
            if (!"tr".equals(scope.tagName())) return null;
            int index = Integer.parseInt(column.group(1));
            List<Element> cells = scope.select("td");
            return index < cells.size() ? clean(cells.get(index).text()) : null;
        }
        boolean text = mapping.endsWith("::text");
        String base = text ? mapping.substring(0, mapping.length() - "::text".length()) : mapping;
        String selector = selector(base);
        String attribute = attribute(base);
        Element selected = scope.selectFirst(selector);
        if (selected == null) return null;
        return clean(attribute == null ? selected.text() : selected.attr(attribute));
    }

    private String selector(String mapping) {
        if (mapping.endsWith("::text")) {
            mapping = mapping.substring(0, mapping.length() - "::text".length());
        }
        int attribute = mapping.lastIndexOf('@');
        String selector = attribute < 0 ? mapping : mapping.substring(0, attribute);
        if (selector.isBlank()) throw new IllegalArgumentException("Mapping selector is required.");
        return selector;
    }

    private String attribute(String mapping) {
        int attribute = mapping.lastIndexOf('@');
        if (attribute < 0) return null;
        String name = mapping.substring(attribute + 1);
        if (!name.matches("[A-Za-z_:][-A-Za-z0-9_:.]*")) {
            throw new IllegalArgumentException("Mapping attribute is invalid.");
        }
        return name;
    }

    private TemplateMatch match(String template, URI target) {
        if (target == null) return null;
        Matcher placeholders = PLACEHOLDER.matcher(template);
        StringBuilder regex = new StringBuilder("^");
        int end = 0;
        int count = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(template.substring(end, placeholders.start())));
            regex.append("([^/?#&]+)");
            end = placeholders.end();
            count++;
        }
        regex.append(Pattern.quote(template.substring(end))).append('$');
        if (count == 0) return null;
        Matcher targetMatcher = Pattern.compile(regex.toString()).matcher(target.toASCIIString());
        if (!targetMatcher.matches()) return null;
        List<String> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) values.add(targetMatcher.group(index));
        return new TemplateMatch(List.copyOf(values));
    }

    private URI generatedDetailUri(
            DiscoveredPublicPageSourceConfig config,
            List<String> capturedValues,
            String scalarIdentifier
    ) {
        Matcher placeholders = PLACEHOLDER.matcher(config.detailUrlPattern());
        StringBuilder generated = new StringBuilder();
        int index = 0;
        while (placeholders.find()) {
            String replacement;
            if (scalarIdentifier != null) replacement = encode(scalarIdentifier);
            else if (index < capturedValues.size()) replacement = capturedValues.get(index++);
            else return null;
            placeholders.appendReplacement(generated, Matcher.quoteReplacement(replacement));
        }
        placeholders.appendTail(generated);
        URI uri = safeResolve(config.listPageUrl(), generated.toString());
        return safeForConfig(config, uri) ? uri : null;
    }

    private URI safeResolve(URI base, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI resolved = base.resolve(URI.create(value.trim())).normalize();
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme))
                    && resolved.getHost() != null
                    && resolved.getRawUserInfo() == null
                    && resolved.getFragment() == null
                    && effectivePort(resolved) > 0 ? resolved : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean safeForConfig(DiscoveredPublicPageSourceConfig config, URI uri) {
        return uri != null
                && config.listPageUrl().getScheme().equalsIgnoreCase(uri.getScheme())
                && config.listPageUrl().getHost().equalsIgnoreCase(uri.getHost())
                && effectivePort(config.listPageUrl()) == effectivePort(uri);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() > 0) return uri.getPort();
        if (uri.getPort() == 0) return -1;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String expression(DiscoveredPublicPageSourceConfig.MappingValue mapping) {
        return mapping.expression().orElseThrow(() -> new IllegalArgumentException("Mapping is UNKNOWN."));
    }

    private String fileName(Element link) {
        String value = clean(link.attr("download"));
        if (value == null) value = clean(link.text());
        if (value == null) return "attachment";
        value = value.replace('\\', '/');
        int separator = value.lastIndexOf('/');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    @FunctionalInterface
    public interface DetailHtmlProvider {
        Optional<String> find(URI detailUri);
    }

    private record ParsedIdentifier(String value, URI detailUri) { }

    private record TemplateMatch(List<String> values) { }
}
