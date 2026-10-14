package com.comhu.bidmonitor.bid.source.kwater;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Collects K-water bid candidates from the official data.go.kr OpenAPI. */
@Component
public class KwaterBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "KWATER";
    static final int PAGE_SIZE = 100;

    private static final String API_HOST = "apis.data.go.kr";
    private static final String API_PATH = "/B500001/ebid/tndr3";
    private static final String DETAIL_URL = "https://ebid.kwater.or.kr/wq/index.do";
    private static final DateTimeFormatter SEARCH_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter NOTICE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SAFE_RESULT_CODE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int MAX_PAGES = 1_000;
    private static final int MAX_RESPONSE_LENGTH = 8 * 1024 * 1024;

    private final URI apiUri;
    private final String serviceKey;
    private final boolean configuredExecutionEnabled;
    private final Transport transport;
    private final ObjectMapper objectMapper;
    private final int pageSize;

    @Autowired
    public KwaterBidCollector(
            @Value("${kwater.api.base-url}") String baseUrl,
            @Value("${kwater.api.service-key:}") String serviceKey,
            @Value("${bid-source.kwater.enabled:false}") boolean configuredExecutionEnabled,
            ObjectMapper objectMapper
    ) {
        this(baseUrl, serviceKey, configuredExecutionEnabled, new JdkTransport(), objectMapper, PAGE_SIZE);
    }

    KwaterBidCollector(
            String baseUrl,
            String serviceKey,
            boolean configuredExecutionEnabled,
            Transport transport
    ) {
        this(baseUrl, serviceKey, configuredExecutionEnabled, transport, new ObjectMapper(), PAGE_SIZE);
    }

    KwaterBidCollector(
            String baseUrl,
            String serviceKey,
            boolean configuredExecutionEnabled,
            Transport transport,
            ObjectMapper objectMapper,
            int pageSize
    ) {
        this.apiUri = validateApiUri(baseUrl);
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.configuredExecutionEnabled = configuredExecutionEnabled;
        this.transport = java.util.Objects.requireNonNull(transport, "transport must not be null");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("K-water page size must be positive.");
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
            throw new IllegalStateException("K-water API service key is not configured.");
        }

        LinkedHashMap<NoticeKey, BidQualificationDto> unique = new LinkedHashMap<>();
        for (YearMonth month : monthsBetween(startDate, endDate)) {
            for (Operation operation : Operation.values()) {
                for (String contractDivisionCode : operation.contractDivisionCodes) {
                    collectOperation(operation, contractDivisionCode, month, startDate, endDate, unique);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private void collectOperation(
            Operation operation,
            String contractDivisionCode,
            YearMonth month,
            LocalDate startDate,
            LocalDate endDate,
            Map<NoticeKey, BidQualificationDto> unique
    ) {
        Set<List<String>> seenPages = new HashSet<>();
        int pageNo = 1;
        int totalCount;
        int returnedPageSize;
        do {
            if (pageNo > MAX_PAGES) {
                throw new IllegalStateException("K-water pagination exceeded the safe page limit.");
            }
            ApiPage page = parsePage(request(createApiUri(operation, contractDivisionCode, month, pageNo)));
            totalCount = page.totalCount();
            returnedPageSize = page.numOfRows();

            List<String> pageIdentity = page.items().stream()
                    .map(item -> text(item, "tndrPbanno"))
                    .toList();
            if (!seenPages.add(pageIdentity)) {
                throw new IllegalStateException("K-water pagination returned the same page repeatedly.");
            }
            if ((long) (pageNo - 1) * returnedPageSize < totalCount && page.items().isEmpty()) {
                throw new IllegalStateException("K-water pagination returned an empty page before the end.");
            }

            for (JsonNode item : page.items()) {
                BidQualificationDto candidate = toQualification(item);
                LocalDate noticeDate = parseNoticeDate(candidate.getBidNtceDt());
                if (candidate.getSourceNoticeId() == null || candidate.getSourceNoticeId().isBlank()
                        || noticeDate == null || noticeDate.isBefore(startDate) || noticeDate.isAfter(endDate)) {
                    continue;
                }
                unique.putIfAbsent(
                        new NoticeKey(candidate.getSourceNoticeId(), candidate.getRevision()), candidate
                );
            }
            pageNo++;
        } while ((long) (pageNo - 1) * returnedPageSize < totalCount);
    }

    private URI createApiUri(
            Operation operation,
            String contractDivisionCode,
            YearMonth month,
            int pageNo
    ) {
        String url = apiUri.toASCIIString() + "/" + operation.path + "?"
                + "serviceKey=" + encodedServiceKey(serviceKey)
                + "&pageNo=" + pageNo
                + "&numOfRows=" + pageSize
                + "&_type=json"
                + "&searchDt=" + month.format(SEARCH_MONTH)
                + (contractDivisionCode.isBlank()
                ? "" : "&cntrctDivCd=" + encode(contractDivisionCode));
        return URI.create(url);
    }

    private String request(URI uri) {
        try {
            return transport.get(uri);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("K-water API request failed.");
        }
    }

    private ApiPage parsePage(String json) {
        if (json == null || json.length() > MAX_RESPONSE_LENGTH) {
            throw new IllegalStateException("K-water API response size is invalid.");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode response = root.path("response").isObject() ? root.path("response") : root;
            validateHeader(response.path("header"));
            JsonNode body = response.path("body");
            if (!body.isObject()) {
                throw new IllegalStateException("K-water API response body is missing.");
            }
            int totalCount = nonNegativeInt(body.path("totalCount"), "totalCount");
            int numOfRows = positiveInt(body.path("numOfRows"), "numOfRows");
            JsonNode itemNode = body.path("items").path("item");
            List<JsonNode> items = new ArrayList<>();
            if (itemNode.isArray()) {
                itemNode.forEach(items::add);
            } else if (itemNode.isObject()) {
                items.add(itemNode);
            } else if (!itemNode.isMissingNode() && !itemNode.isNull()) {
                throw new IllegalStateException("K-water API items are malformed.");
            }
            return new ApiPage(totalCount, numOfRows, List.copyOf(items));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("K-water API response could not be parsed.", exception);
        }
    }

    private void validateHeader(JsonNode header) {
        if (!header.isObject()) {
            throw new IllegalStateException("K-water API response header is missing.");
        }
        String resultCode = text(header, "resultCode");
        if (!"00".equals(resultCode)) {
            String safeCode = SAFE_RESULT_CODE.matcher(resultCode).matches() ? resultCode : "UNKNOWN";
            throw new IllegalStateException("K-water API returned error code " + safeCode + ".");
        }
    }

    private BidQualificationDto toQualification(JsonNode item) {
        String noticeId = text(item, "tndrPbanno");
        BidQualificationDto value = new BidQualificationDto();
        value.setSourceCode(SOURCE_CODE);
        value.setSourceNoticeId(noticeId);
        value.setRevision(null);
        value.setBidNtceNo(noticeId);
        value.setBidNtceNm(text(item, "tndrPblancNm"));
        value.setNtceInsttNm("한국수자원공사");
        value.setBidNtceDt(dateText(item, "tndrPblancDe"));
        value.setBidClseDt(dateText(item, "tndrPblancEnddt"));
        value.setBidOpeningDt(dateText(item, "cardPrcsDt"));
        value.setContractMethod(firstNonBlank(text(item, "ctrmthdCdNm"), text(item, "ctrmthdNm")));
        value.setBidForm(text(item, "cntrctDivNm"));
        value.setNoticeStatus(firstNonBlank(text(item, "tndrStat"), text(item, "cntrctPrgstsNm")));
        value.setAsignBdgtAmt(firstNonBlank(text(item, "tndrPlnprc"), text(item, "scsbidAmt")));
        value.setSucsfbidMthdNm(text(item, "sucbidrDcsnMthNm"));
        value.setLicenseLimit("");
        value.setLicenseGroups(List.of());
        value.setAttachments(List.of());
        if (!noticeId.isBlank()) {
            String detailUrl = createDetailUrl(noticeId);
            value.setDetailUrl(detailUrl);
            value.setBidNtceDtlUrl(detailUrl);
        }
        return value;
    }

    private static String createDetailUrl(String noticeId) {
        return DETAIL_URL
                + "?tndrPbanno=" + encode(noticeId)
                + "&view=" + encode("/bidpblanc/bidpblancsttus/BIDBD32000002.xml")
                + "&w2xPath=" + encode("/ui/index.xml");
    }

    private static List<YearMonth> monthsBetween(LocalDate startDate, LocalDate endDate) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(startDate);
        YearMonth last = YearMonth.from(endDate);
        while (!current.isAfter(last)) {
            months.add(current);
            current = current.plusMonths(1);
        }
        return List.copyOf(months);
    }

    private static LocalDate parseNoticeDate(String value) {
        try {
            return LocalDate.parse(value, NOTICE_DATE);
        } catch (DateTimeParseException | NullPointerException exception) {
            return null;
        }
    }

    private static int nonNegativeInt(JsonNode value, String field) {
        if (!value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalStateException("K-water API " + field + " is invalid.");
        }
        return value.intValue();
    }

    private static int positiveInt(JsonNode value, String field) {
        if (!value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalStateException("K-water API " + field + " is invalid.");
        }
        return value.intValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String dateText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() || "-".equals(value) ? null : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
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
            throw new IllegalArgumentException("K-water API base URL is invalid.", exception);
        }
        String path = uri.getPath();
        if (path != null && path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !API_HOST.equalsIgnoreCase(uri.getHost())
                || !API_PATH.equals(path) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("K-water API base URL is invalid.");
        }
        return URI.create("https://" + API_HOST + API_PATH);
    }

    private static void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("K-water notice date range is invalid.");
        }
    }

    enum Operation {
        SERVC("servcList", List.of("")),
        CNTRWK("cntrwkList", List.of("")),
        DMSCPT("dmscptList", List.of("")),
        GDS("gdsList", List.of("")),
        RST("rstList", List.of("1", "2", "3", "4"));

        private final String path;
        private final List<String> contractDivisionCodes;

        Operation(String path, List<String> contractDivisionCodes) {
            this.path = path;
            this.contractDivisionCodes = contractDivisionCodes;
        }
    }

    private record ApiPage(int totalCount, int numOfRows, List<JsonNode> items) {
    }

    private record NoticeKey(String sourceNoticeId, String revision) {
    }

    @FunctionalInterface
    interface Transport {
        String get(URI uri);
    }

    static final class JdkTransport implements Transport {
        private final HttpClient client;

        JdkTransport() {
            this(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
        }

        JdkTransport(HttpClient client) {
            this.client = client;
        }

        @Override
        public String get(URI uri) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "K-water API request failed with HTTP " + response.statusCode() + "."
                    );
                }
                if (response.body() == null || response.body().length() > MAX_RESPONSE_LENGTH) {
                    throw new IllegalStateException("K-water API response size is invalid.");
                }
                return response.body();
            } catch (IOException exception) {
                throw new IllegalStateException("K-water API request failed.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("K-water API request was interrupted.");
            }
        }
    }
}
