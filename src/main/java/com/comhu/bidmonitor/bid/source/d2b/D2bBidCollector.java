package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 방위사업청 공식 공공데이터 OpenAPI에서 D2B 입찰 후보를 수집한다. */
@Component
public class D2bBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "D2B";
    static final int PAGE_SIZE = 100;
    static final int MAX_REQUESTS_PER_COLLECTION = 100;

    private static final Logger log = LoggerFactory.getLogger(D2bBidCollector.class);
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> SEARCH_TERMS = List.of(
            "정보시스템 감리", "정보화 감리", "개인정보 영향평가", "개인정보영향평가", "감리"
    );
    private static final List<String> IT_CONTEXT = List.of(
            "정보시스템", "정보화", "전산", "소프트웨어", "시스템", "ict",
            "플랫폼", "구축", "운영", "유지관리", "클라우드"
    );
    private static final List<String> NON_IT_SUPERVISION = List.of("건설", "소방", "전기");

    private final String baseUrl;
    private final String serviceKey;
    private final Transport transport;
    private final CallQuota callQuota;

    @Autowired
    public D2bBidCollector(
            @Value("${d2b.api.base-url}") String baseUrl,
            @Value("${d2b.api.service-key:}") String serviceKey,
            D2bDailyQuotaService dailyQuotaService
    ) {
        this(baseUrl, serviceKey, new JdkTransport(), dailyQuotaService::reserve);
    }

    D2bBidCollector(String baseUrl, String serviceKey, Transport transport) {
        this(baseUrl, serviceKey, transport, () -> { });
    }

    D2bBidCollector(String baseUrl, String serviceKey, Transport transport, CallQuota callQuota) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("D2B API 기본 URL이 비어 있습니다.");
        }
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.transport = transport;
        this.callQuota = callQuota;
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        return collectMeasured(startDate, endDate).candidates();
    }

    public CollectionResult collectMeasured(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("D2B API 인증키가 설정되지 않았습니다.");
        }

        RequestBudget budget = new RequestBudget(MAX_REQUESTS_PER_COLLECTION);
        RequestCounter counter = new RequestCounter();
        LinkedHashMap<CandidateKey, ListedNotice> listed = new LinkedHashMap<>();
        int successfulOperations = 0;
        List<String> failedOperations = new ArrayList<>();

        for (Operation operation : Operation.values()) {
            try {
                for (ListedNotice notice : collectOperation(operation, startDate, endDate, budget, counter)) {
                    listed.putIfAbsent(notice.key(), notice);
                }
                successfulOperations++;
            } catch (RuntimeException exception) {
                failedOperations.add(operation.listPath);
                log.warn("D2B list operation failed: operation={}, errorType={}",
                        operation.listPath, exception.getClass().getSimpleName());
            }
        }

        if (successfulOperations == 0) {
            throw new CollectionException("D2B 목록 오퍼레이션 조회에 실패했습니다: "
                    + String.join(", ", failedOperations), counter.count());
        }

        List<BidQualificationDto> result = new ArrayList<>();
        for (ListedNotice notice : listed.values()) {
            Element detail = null;
            if (budget.tryAcquire()) {
                try {
                    detail = readDetail(notice, counter);
                } catch (RuntimeException exception) {
                    log.warn("D2B detail operation failed: operation={}, sourceNoticeId={}, errorType={}",
                            notice.operation.detailPath, notice.sourceNoticeId(),
                            exception.getClass().getSimpleName());
                }
            }
            result.add(toQualification(notice, detail));
        }
        return new CollectionResult(result, counter.count());
    }

    private List<ListedNotice> collectOperation(
            Operation operation,
            LocalDate startDate,
            LocalDate endDate,
            RequestBudget budget,
            RequestCounter counter
    ) {
        LinkedHashMap<CandidateKey, ListedNotice> notices = new LinkedHashMap<>();
        for (String term : SEARCH_TERMS) {
            int page = 1;
            int totalCount;
            do {
                budget.acquire();
                Map<String, String> parameters = new LinkedHashMap<>();
                parameters.put("pageNo", Integer.toString(page));
                parameters.put("numOfRows", Integer.toString(PAGE_SIZE));
                parameters.put(operation.titleParameter, term);
                parameters.put(operation.startDateParameter, startDate.format(REQUEST_DATE));
                parameters.put(operation.endDateParameter, endDate.format(REQUEST_DATE));

                Document document = request(operation.listPath, parameters, counter);
                totalCount = integer(text(first(document, "totalCount")), 0);
                for (Element item : elements(document, "item")) {
                    String title = firstText(item, operation.titleField, "bidNm", "othbcNtatNm", "cntrwkNm");
                    if (!isRelevantTitle(title)) {
                        continue;
                    }
                    ListedNotice notice = new ListedNotice(operation, fields(item));
                    validateIdentity(notice);
                    notices.putIfAbsent(notice.key(), notice);
                }
                page++;
            } while ((page - 1) * PAGE_SIZE < totalCount);
        }
        return new ArrayList<>(notices.values());
    }

    private Element readDetail(ListedNotice notice, RequestCounter counter) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String name : notice.operation.detailParameters) {
            String value = notice.value(name);
            if (value.isBlank()) {
                throw new IllegalStateException("D2B 상세조회 식별 필드가 비어 있습니다: " + name);
            }
            parameters.put(name, value);
        }
        return first(request(notice.operation.detailPath, parameters, counter), "item");
    }

    private Document request(String path, Map<String, String> parameters, RequestCounter counter) {
        StringBuilder url = new StringBuilder(baseUrl).append('/').append(path)
                .append("?serviceKey=").append(encodedServiceKey(serviceKey));
        parameters.forEach((name, value) -> url.append('&').append(encode(name)).append('=').append(encode(value)));
        callQuota.reserve();
        counter.recordAttempt();
        String body = transport.get(URI.create(url.toString()));
        Document document = parseXml(body);
        String resultCode = text(first(document, "resultCode"));
        if (!resultCode.isBlank() && !"00".equals(resultCode) && !"0".equals(resultCode)) {
            throw new IllegalStateException("D2B API가 오류 코드를 반환했습니다: " + resultCode);
        }
        return document;
    }

    BidQualificationDto toQualification(ListedNotice notice, Element detail) {
        Map<String, String> values = new LinkedHashMap<>(notice.values);
        if (detail != null) {
            fields(detail).forEach((name, value) -> {
                if (!value.isBlank()) {
                    values.put(name, value);
                }
            });
        }

        BidQualificationDto dto = new BidQualificationDto();
        dto.setSourceCode(SOURCE_CODE);
        dto.setSourceNoticeId(notice.sourceNoticeId());
        dto.setRevision(nullable(values.get("pblancOdr")));
        dto.setDetailUrl(null);
        dto.setBidNtceDtlUrl(null);
        dto.setBidNtceNo(values.getOrDefault("pblancNo", ""));
        dto.setBidNtceNm(firstValue(values, notice.operation.titleField, "bidNm", "othbcNtatNm", "cntrwkNm"));
        dto.setNtceInsttNm(values.getOrDefault("ornt", ""));
        dto.setBidNtceDt(firstValue(values, "pblancDate", "ntatPlanDate"));
        dto.setBidClseDt(firstValue(values, "biddocPresentnClosDt", "prqudoPresentnClosDt"));
        dto.setBidOpeningDt(values.getOrDefault("opengDt", ""));
        dto.setAsignBdgtAmt(firstValue(values, "budgetAmount", "bsicExpt", "baseAmnt"));
        dto.setContractMethod(values.getOrDefault("cntrctMth", ""));
        dto.setBidForm(firstValue(values, "bidStle", "ntatStle"));
        dto.setNoticeStatus(firstValue(values, "pblancSe", "progrsSttus"));
        dto.setNoticeStatusCode(values.getOrDefault("pblancSeCode", ""));
        dto.setSucsfbidMthdNm(values.getOrDefault("sucbidrDecsnMth", ""));
        dto.setLicenseLimit(values.getOrDefault("lcnsLmttList", ""));
        dto.setParticipationRegion(values.getOrDefault("areaLmttList", ""));
        dto.setAttachments(List.of());
        dto.setLicenseGroups(List.of());
        return dto;
    }

    static boolean isRelevantTitle(String title) {
        String normalized = title == null ? "" : title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("개인정보영향평가")) {
            return true;
        }
        if (!normalized.contains("감리")) {
            return false;
        }
        boolean hasItContext = IT_CONTEXT.stream().anyMatch(normalized::contains)
                || normalized.matches(".*(^|[^a-z])it([^a-z]|$).*");
        if (NON_IT_SUPERVISION.stream().anyMatch(normalized::contains) && !hasItContext) {
            return false;
        }
        return hasItContext;
    }

    private static void validateIdentity(ListedNotice notice) {
        if (notice.value("pblancNo").isBlank() || notice.value("orntCode").isBlank()) {
            throw new IllegalStateException("D2B 목록 응답에 공고 식별 필드가 없습니다.");
        }
    }

    private static Document parseXml(String xml) {
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
            throw new IllegalStateException("D2B API XML 응답을 처리할 수 없습니다.", exception);
        }
    }

    private static List<Element> elements(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element first(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    private static Map<String, String> fields(Element element) {
        Map<String, String> result = new LinkedHashMap<>();
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element childElement) {
                result.put(childElement.getTagName(), childElement.getTextContent().trim());
            }
        }
        return result;
    }

    private static String firstText(Element element, String... names) {
        return firstValue(fields(element), names);
    }

    private static String firstValue(Map<String, String> values, String... names) {
        for (String name : names) {
            String value = values.getOrDefault(name, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodedServiceKey(String value) {
        return value.matches(".*%[0-9a-fA-F]{2}.*") ? value : encode(value);
    }

    private static void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("D2B 공고 조회 시작일과 종료일을 확인해 주세요.");
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    enum Operation {
        DOMESTIC_COMPETITIVE(
                "getDmstcCmpetBidPblancList", "getDmstcCmpetBidPblancDetail",
                "bidNm", "bidNm", "anmtDateBegin", "anmtDateEnd",
                List.of("demandYear", "orntCode", "dcsNo", "pblancNo", "pblancOdr")
        ),
        DOMESTIC_NEGOTIATION(
                "getDmstcOthbcVltrnNtatPlanList", "getDmstcOthbcVltrnNtatPlanDetail",
                "othbcNtatNm", "othbcNtatNm", "prqudoPresentnClosDateBegin", "prqudoPresentnClosDateEnd",
                List.of("demandYear", "orntCode", "dcsNo", "iemNo", "pblancNo", "pblancOdr", "ntatPlanDate")
        ),
        FACILITY_COMPETITIVE(
                "getFcltyCmpetBidPblancList", "getFcltyCmpetBidPblancDetail",
                "cntrwkNm", "cntrwkNm", "anmtDateBegin", "anmtDateEnd",
                List.of("pblancYear", "pblancSeCode", "pblancNo", "pblancOdr", "cntrwkNo", "orntCode")
        ),
        FACILITY_NEGOTIATION(
                "getFcltyOthbcVltrnNtatPlanList", "getFcltyOthbcVltrnNtatPlanDetail",
                "othbcNtatNm", "cntrwkNm", "prqudoPresentnClosDateBegin", "prqudoPresentnClosDateEnd",
                List.of("orntCode", "cntrwkNo", "ntatPlanDate", "pblancNo", "pblancOdr")
        );

        private final String listPath;
        private final String detailPath;
        private final String titleParameter;
        private final String titleField;
        private final String startDateParameter;
        private final String endDateParameter;
        private final List<String> detailParameters;

        Operation(
                String listPath,
                String detailPath,
                String titleParameter,
                String titleField,
                String startDateParameter,
                String endDateParameter,
                List<String> detailParameters
        ) {
            this.listPath = listPath;
            this.detailPath = detailPath;
            this.titleParameter = titleParameter;
            this.titleField = titleField;
            this.startDateParameter = startDateParameter;
            this.endDateParameter = endDateParameter;
            this.detailParameters = detailParameters;
        }
    }

    record ListedNotice(Operation operation, Map<String, String> values) {
        private String value(String name) {
            return values.getOrDefault(name, "");
        }

        private String sourceNoticeId() {
            return noticeYear() + ":" + value("orntCode") + ":" + value("pblancNo");
        }

        private String noticeYear() {
            String year = firstValue(values, "demandYear", "pblancYear");
            if (!year.isBlank()) {
                return year;
            }
            String date = firstValue(values, "pblancDate", "ntatPlanDate", "prqudoPresentnClosDt");
            return date.length() >= 4 ? date.substring(0, 4) : "UNKNOWN";
        }

        private CandidateKey key() {
            return new CandidateKey(sourceNoticeId(), nullable(value("pblancOdr")));
        }
    }

    private record CandidateKey(String sourceNoticeId, String revision) {
    }

    public record CollectionResult(List<BidQualificationDto> candidates, int apiCallCount) {
        public CollectionResult {
            candidates = List.copyOf(candidates);
            if (apiCallCount < 0) {
                throw new IllegalArgumentException("apiCallCount must not be negative.");
            }
        }
    }

    public static class CollectionException extends IllegalStateException {
        private final int apiCallCount;

        CollectionException(String message, int apiCallCount) {
            super(message);
            this.apiCallCount = apiCallCount;
        }

        public int getApiCallCount() {
            return apiCallCount;
        }
    }

    private static final class RequestCounter {
        private int count;

        private void recordAttempt() {
            count++;
        }

        private int count() {
            return count;
        }
    }

    private static final class RequestBudget {
        private int remaining;

        private RequestBudget(int remaining) {
            this.remaining = remaining;
        }

        private void acquire() {
            if (!tryAcquire()) {
                throw new IllegalStateException("D2B API 수집당 호출 한도를 초과했습니다.");
            }
        }

        private boolean tryAcquire() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    @FunctionalInterface
    interface Transport {
        String get(URI uri);
    }

    @FunctionalInterface
    interface CallQuota {
        void reserve();
    }

    private static final class JdkTransport implements Transport {
        private static final Duration TIMEOUT = Duration.ofSeconds(30);
        private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public String get(URI uri) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/xml")
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("D2B API HTTP 요청에 실패했습니다: " + response.statusCode());
                    }
                    byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("D2B API 응답 크기 제한을 초과했습니다.");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("D2B API 통신에 실패했습니다.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("D2B API 통신이 중단되었습니다.", exception);
            }
        }
    }
}
