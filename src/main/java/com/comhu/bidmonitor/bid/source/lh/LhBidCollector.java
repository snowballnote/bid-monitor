package com.comhu.bidmonitor.bid.source.lh;

import com.comhu.bidmonitor.bid.license.LicenseNormalizer;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** LH 공식 입찰공고 OpenAPI를 공통 후보 모델로 변환한다. */
@Component
public class LhBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "LH";
    static final int PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(LhBidCollector.class);
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final URI DETAIL_BASE_URI = URI.create("https://ebid.lh.or.kr/");
    private static final String DETAIL_PATH = "ebid.et.tp.cmd.BidsrvcsDetailListCmd.dev";
    private static final String DETAIL_CA_CERTIFICATE =
            "/certificates/lh-turingsign-rsa-secure-ca-2.pem";
    private static final String API_HOST = "apis.data.go.kr";
    private static final String API_PATH = "/B552555/OpenBidInfoList/getOpenBidInfo";
    private static final int MAX_PAGES = 1_000;
    private static final int MAX_RESPONSE_LENGTH = 8 * 1024 * 1024;
    private final URI apiUri;
    private final String serviceKey;
    private final boolean configuredExecutionEnabled;
    private final Transport transport;
    private final int pageSize;
    @Autowired
    public LhBidCollector(
            @Value("${lh.api.base-url}") String baseUrl,
            @Value("${lh.api.service-key:}") String serviceKey,
            @Value("${bid-source.lh.enabled:false}") boolean configuredExecutionEnabled
    ) {
        this(baseUrl, serviceKey, configuredExecutionEnabled, new JdkTransport(), PAGE_SIZE);
    }

    LhBidCollector(String baseUrl, String serviceKey, boolean configuredExecutionEnabled, Transport transport) {
        this(baseUrl, serviceKey, configuredExecutionEnabled, transport, PAGE_SIZE);
    }

    LhBidCollector(
            String baseUrl,
            String serviceKey,
            boolean configuredExecutionEnabled,
            Transport transport,
            int pageSize
    ) {
        this.apiUri = validateApiUri(baseUrl);
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.configuredExecutionEnabled = configuredExecutionEnabled;
        this.transport = transport;
        if (pageSize < 1) {
            throw new IllegalArgumentException("LH page size must be positive.");
        }
        this.pageSize = pageSize;
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    public boolean executionEnabled() {
        return configuredExecutionEnabled && !serviceKey.isBlank();
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("LH API service key is not configured.");
        }

        LinkedHashMap<NoticeKey, Map<String, String>> uniqueNotices = collectApiNotices(startDate, endDate);
        List<BidQualificationDto> result = new ArrayList<>();
        for (Map<String, String> notice : uniqueNotices.values()) {
            DetailData detail = DetailData.empty();
            if (needsDetail(notice) && isServiceNotice(notice)) {
                try {
                    detail = parseDetail(transport.get(createDetailUri(notice)));
                } catch (RuntimeException exception) {
                    log.warn("LH detail collection failed: bidNum={}, bidDegree={}, errorType={}",
                            notice.get("bidNum"), notice.get("bidDegree"),
                            exception.getClass().getSimpleName());
                }
            }
            result.add(toQualification(notice, detail));
        }
        return List.copyOf(result);
    }

    private LinkedHashMap<NoticeKey, Map<String, String>> collectApiNotices(
            LocalDate startDate,
            LocalDate endDate
    ) {
        LinkedHashMap<NoticeKey, Map<String, String>> unique = new LinkedHashMap<>();
        Set<List<NoticeKey>> seenPages = new HashSet<>();
        int pageNo = 1;
        int totalCount;
        do {
            if (pageNo > MAX_PAGES) {
                throw new IllegalStateException("LH pagination exceeded the safe page limit.");
            }
            ApiPage page = parseApiPage(transport.get(createApiUri(startDate, endDate, pageNo)));
            totalCount = page.totalCount();
            List<NoticeKey> pageIdentity = page.items().stream().map(this::noticeKey).toList();
            if (!seenPages.add(pageIdentity)) {
                throw new IllegalStateException("LH pagination returned the same page repeatedly.");
            }
            if ((long) (pageNo - 1) * pageSize < totalCount && page.items().isEmpty()) {
                throw new IllegalStateException("LH pagination returned an empty page before the end.");
            }
            for (Map<String, String> item : page.items()) {
                NoticeKey key = noticeKey(item);
                if (!key.bidNum().isBlank() && !key.bidDegree().isBlank()) {
                    unique.putIfAbsent(key, item);
                }
            }
            pageNo++;
        } while ((long) (pageNo - 1) * pageSize < totalCount);
        return unique;
    }

    private URI createApiUri(LocalDate startDate, LocalDate endDate, int pageNo) {
        String url = apiUri.toASCIIString() + "?"
                + "serviceKey=" + encodedServiceKey(serviceKey)
                + "&pageNo=" + pageNo
                + "&numOfRows=" + pageSize
                + "&tndrbidRegDtStart=" + startDate.format(REQUEST_DATE)
                + "&tndrbidRegDtEnd=" + endDate.format(REQUEST_DATE);
        return URI.create(url);
    }

    private URI createDetailUri(Map<String, String> notice) {
        return DETAIL_BASE_URI.resolve(DETAIL_PATH + "?bidDegree=" + encode(notice.get("bidDegree"))
                + "&bidNum=" + encode(notice.get("bidNum")));
    }

    private ApiPage parseApiPage(String xml) {
        org.w3c.dom.Document document = parseXml(xml);
        String resultCode = firstText(document, "resultCode");
        if (!resultCode.isBlank() && !"00".equals(resultCode) && !"0".equals(resultCode)) {
            throw new IllegalStateException("LH API returned a failure result code.");
        }
        int totalCount = parseNonNegativeInt(firstText(document, "totalCount"));
        List<Map<String, String>> items = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("item");
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof org.w3c.dom.Element element) {
                items.add(fields(element));
            }
        }
        return new ApiPage(totalCount, List.copyOf(items));
    }

    DetailData parseDetail(String html) {
        if (html == null || html.length() > MAX_RESPONSE_LENGTH) {
            throw new IllegalStateException("LH detail response size is invalid.");
        }
        Document document = Jsoup.parse(html, DETAIL_BASE_URI.toASCIIString());
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element row : document.select("tr")) {
            Elements headers = row.select("th");
            Elements values = row.select("td");
            int count = Math.min(headers.size(), values.size());
            for (int index = 0; index < count; index++) {
                putField(fields, headers.get(index).text(), values.get(index).text());
            }
        }

        List<String> requirementNames = new ArrayList<>();
        for (Element table : document.select("table")) {
            if (!table.text().contains("요구면허")) {
                continue;
            }
            Elements headerCells = table.selectFirst("tr") == null
                    ? new Elements() : table.selectFirst("tr").select("th,td");
            List<Integer> requirementColumns = new ArrayList<>();
            for (int index = 0; index < headerCells.size(); index++) {
                if (normalizeLabel(headerCells.get(index).text()).startsWith("요구면허")) {
                    requirementColumns.add(index);
                }
            }
            for (Element row : table.select("tr").stream().skip(1).toList()) {
                Elements cells = row.select("th,td");
                for (Integer column : requirementColumns) {
                    if (column < cells.size()) {
                        addRequirement(requirementNames, cells.get(column).text());
                    }
                }
            }
        }
        addRequirement(requirementNames, field(fields, "입찰참가자격", "참가자격", "요구면허"));
        return new DetailData(Map.copyOf(fields), List.copyOf(requirementNames));
    }

    BidQualificationDto toQualification(Map<String, String> notice, DetailData detail) {
        BidQualificationDto value = new BidQualificationDto();
        value.setSourceCode(SOURCE_CODE);
        value.setSourceNoticeId(notice.getOrDefault("bidNum", ""));
        value.setRevision(nullable(notice.get("bidDegree")));
        String detailUrl = createDetailUri(notice).toASCIIString();
        value.setDetailUrl(detailUrl);
        value.setBidNtceDtlUrl(detailUrl);
        value.setBidNtceNo(notice.getOrDefault("bidNum", ""));
        value.setBidNtceNm(notice.getOrDefault("bidnmKor", ""));
        value.setNtceInsttNm("한국토지주택공사");
        value.setBidNtceDt(notice.getOrDefault("tndrbidRegDt", ""));
        value.setBidClseDt(notice.getOrDefault("tndrdocAcptEndDtm", ""));
        value.setBidOpeningDt(notice.getOrDefault("openDtm", ""));
        value.setContractMethod(firstNonBlank(
                notice.get("tndrCtrctMedCd"), field(detail.fields(), "계약방법")
        ));
        value.setBidForm(field(detail.fields(), "입찰방법", "입찰방식"));
        value.setNoticeStatus(notice.getOrDefault("bidProgrsStatus", ""));
        value.setAsignBdgtAmt(firstNonBlank(notice.get("presmtPrc"), notice.get("fdmtlAmt")));
        value.setAttachments(List.of());

        List<LicenseRequirementGroup> apiGroups = parseApiLicenseGroups(notice);
        List<LicenseRequirementGroup> detailGroups = parseDetailLicenseGroups(detail.requirementNames());
        List<LicenseRequirementGroup> licenseGroups = apiGroups.isEmpty() ? detailGroups : apiGroups;
        value.setLicenseGroups(licenseGroups);
        value.setLicenseLimit(licenseText(licenseGroups));

        value.setParticipationRegion(firstNonBlank(
                joinNonBlank(notice, "zoneRstrct1", "zoneRstrct2", "zoneRstrct3", "zoneRstrct4"),
                joinNonBlank(detail.fields(), "참가지역1", "참가지역2", "참가지역3", "참가지역4", "지역제한")
        ));
        value.setSucsfbidMthdNm(firstNonBlank(
                notice.get("sunjungNm"), field(detail.fields(), "낙찰자선정방법", "낙찰방법")
        ));
        value.setPqEvalYn(normalizeYesNo(field(detail.fields(), "PQ심사실시여부", "PQ심사여부")));
        return value;
    }

    static List<LicenseRequirementGroup> parseApiLicenseGroups(Map<String, String> notice) {
        List<LicenseRequirementGroup> groups = new ArrayList<>();
        for (int groupNo = 1; groupNo <= 10; groupNo++) {
            List<LicenseRequirement> requirements = new ArrayList<>();
            for (int licenseNo = 1; licenseNo <= 10; licenseNo++) {
                String name = clean(notice.get("req" + groupNo + "Reqlic" + licenseNo + "Nm"));
                if (name != null) {
                    requirements.add(requirement(requirements.size() + 1, name));
                }
            }
            if (!requirements.isEmpty()) {
                groups.add(new LicenseRequirementGroup(Integer.toString(groupNo), List.copyOf(requirements)));
            }
        }
        return List.copyOf(groups);
    }

    static List<LicenseRequirementGroup> parseDetailLicenseGroups(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<LicenseRequirement> requirements = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            String cleaned = clean(name);
            if (cleaned != null) {
                requirements.add(requirement(requirements.size() + 1, cleaned));
            }
        }
        return requirements.isEmpty() ? List.of()
                : List.of(new LicenseRequirementGroup("1", List.copyOf(requirements)));
    }

    private static LicenseRequirement requirement(int sequence, String rawName) {
        return LicenseNormalizer.normalizeVerifiedName(rawName)
                .map(license -> new LicenseRequirement(
                        Integer.toString(sequence), license.code(), license.officialName(), rawName
                ))
                .orElseGet(() -> new LicenseRequirement(
                        Integer.toString(sequence), "", rawName, rawName
                ));
    }

    private static boolean needsDetail(Map<String, String> notice) {
        return parseApiLicenseGroups(notice).isEmpty()
                || clean(notice.get("tndrCtrctMedCd")) == null
                || clean(notice.get("sunjungNm")) == null;
    }

    private static boolean isServiceNotice(Map<String, String> notice) {
        String type = clean(notice.get("cstrtnJobGbNm"));
        return type != null && type.contains("용역");
    }

    private NoticeKey noticeKey(Map<String, String> item) {
        return new NoticeKey(item.getOrDefault("bidNum", "").trim(),
                item.getOrDefault("bidDegree", "").trim());
    }

    private static String licenseText(List<LicenseRequirementGroup> groups) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (LicenseRequirementGroup group : groups) {
            if (group != null && group.getRequirements() != null) {
                for (LicenseRequirement requirement : group.getRequirements()) {
                    String name = requirement == null ? null : clean(requirement.getRawLicenseLimitName());
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return String.join(", ", names);
    }

    private static String joinNonBlank(Map<String, String> fields, String... names) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String name : names) {
            String value = clean(fields.get(name));
            if (value != null) {
                values.add(value);
            }
        }
        return String.join(", ", values);
    }

    private static String field(Map<String, String> fields, String... labels) {
        for (String label : labels) {
            String value = clean(fields.get(normalizeLabel(label)));
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static void putField(Map<String, String> fields, String label, String value) {
        String cleaned = clean(value);
        if (cleaned != null) {
            fields.putIfAbsent(normalizeLabel(label), cleaned);
        }
    }

    private static void addRequirement(List<String> values, String value) {
        String cleaned = clean(value);
        if (cleaned != null && !"-".equals(cleaned) && !"해당 자료가 없음".equals(cleaned)) {
            values.add(cleaned);
        }
    }

    private static String normalizeLabel(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned.replaceAll("[\\s:*]", "");
    }

    private static String normalizeYesNo(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return "";
        }
        if (Set.of("예", "Y", "실시", "접수").contains(normalized)) {
            return "Y";
        }
        if (Set.of("아니오", "N", "미실시", "미접수").contains(normalized)) {
            return "N";
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        String firstValue = clean(first);
        return firstValue == null ? (clean(second) == null ? "" : clean(second)) : firstValue;
    }

    private static String nullable(String value) {
        return clean(value);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static int parseNonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("LH API totalCount is invalid.");
        }
    }

    private static Map<String, String> fields(org.w3c.dom.Element element) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                values.put(node.getNodeName(), node.getTextContent() == null ? "" : node.getTextContent().trim());
            }
        }
        return Map.copyOf(values);
    }

    private static org.w3c.dom.Document parseXml(String xml) {
        if (xml == null || xml.length() > MAX_RESPONSE_LENGTH) {
            throw new IllegalStateException("LH API response size is invalid.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IllegalStateException("LH API response could not be parsed.", exception);
        }
    }

    private static String firstText(org.w3c.dom.Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        return nodes.getLength() == 0 || nodes.item(0).getTextContent() == null
                ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String encodedServiceKey(String value) {
        return value.matches(".*%[0-9A-Fa-f]{2}.*") ? value : encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static URI validateApiUri(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LH API base URL is invalid.", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !API_HOST.equalsIgnoreCase(uri.getHost())
                || !API_PATH.equals(uri.getPath()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("LH API base URL is invalid.");
        }
        return uri;
    }

    private static void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("LH notice date range is invalid.");
        }
    }

    record DetailData(Map<String, String> fields, List<String> requirementNames) {
        static DetailData empty() {
            return new DetailData(Map.of(), List.of());
        }
    }

    private record ApiPage(int totalCount, List<Map<String, String>> items) {
    }

    private record NoticeKey(String bidNum, String bidDegree) {
    }

    @FunctionalInterface
    interface Transport {
        String get(URI uri);
    }

    static final class JdkTransport implements Transport {
        private final HttpClient apiClient;
        private final HttpClient detailClient;

        JdkTransport() {
            this(httpClient(HttpClient.Redirect.NEVER, null),
                    httpClient(HttpClient.Redirect.NORMAL, detailSslContext()));
        }

        JdkTransport(HttpClient apiClient, HttpClient detailClient) {
            this.apiClient = apiClient;
            this.detailClient = detailClient;
        }

        @Override
        public String get(URI uri) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/xml,text/html")
                        .GET()
                        .build();
                HttpClient client = DETAIL_BASE_URI.getHost().equalsIgnoreCase(uri.getHost())
                        ? detailClient : apiClient;
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("LH request failed with HTTP " + response.statusCode());
                }
                if (response.body().length() > MAX_RESPONSE_LENGTH) {
                    throw new IllegalStateException("LH response size limit exceeded.");
                }
                return response.body();
            } catch (IOException exception) {
                throw new IllegalStateException("LH request failed.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LH request was interrupted.", exception);
            }
        }

        static HttpClient httpClient(HttpClient.Redirect redirects, SSLContext sslContext) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(redirects);
            return (sslContext == null ? builder : builder.sslContext(sslContext)).build();
        }

        private static SSLContext detailSslContext() {
            try (InputStream input = LhBidCollector.class.getResourceAsStream(DETAIL_CA_CERTIFICATE)) {
                if (input == null) {
                    throw new IllegalStateException("LH detail CA certificate is missing.");
                }
                X509Certificate certificate = (X509Certificate) CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(input);
                certificate.checkValidity();

                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("lh-detail-ca", certificate);

                TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                );
                trustManagers.init(trustStore);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagers.getTrustManagers(), null);
                return context;
            } catch (GeneralSecurityException | IOException exception) {
                throw new IllegalStateException("LH detail TLS configuration failed.", exception);
            }
        }
    }
}
