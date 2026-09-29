package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/** 한국가스공사 공개 입찰공고 페이지를 fixture로 검증 가능한 후보 수집기로 변환한다. */
@Slf4j
@Component
public class KogasBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "KOGAS";
    static final String LIST_PATH = "/supplier/contents/bid/bid_list_notice_frm.jsp";
    static final String DETAIL_PATH = "/supplier/contents/bid/bid_detail_view_notice.jsp";
    static final String ATTACHMENT_PATH = "/supplier/bid/bid_download_attfile.jsp";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)(?:charset\\s*=\\s*|<meta[^>]+charset\\s*=\\s*[\\\"']?)([a-zA-Z0-9._-]+)"
    );
    private static final Pattern NAMED_PARAMETER_PATTERN = Pattern.compile(
            "(?i)(notice_code|bid_code|round)\\s*[=:,]\\s*[\\\"']?([^\\\"'&,)\\s]+)"
    );
    private static final List<DateTimeFormatter> SOURCE_DATE_TIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );
    private static final List<DateTimeFormatter> SOURCE_DATES = List.of(
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );
    private static final DateTimeFormatter TARGET_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final URI baseUri;
    private final Transport transport;
    private final Predicate<BidCandidateCollector> executionEligibility;

    @Autowired
    public KogasBidCollector(
            @Value("${bid-source.kogas.base-url:https://bid.kogas.or.kr:9443}") String baseUrl,
            BidSourceExecutionEligibilityService executionEligibility
    ) {
        this(baseUrl, new SafeHttpTransport(), executionEligibility::isEligible);
    }

    KogasBidCollector(String baseUrl, Transport transport) {
        this(baseUrl, transport, collector -> true);
    }

    KogasBidCollector(
            String baseUrl,
            Transport transport,
            Predicate<BidCandidateCollector> executionEligibility
    ) {
        this.baseUri = validateBaseUri(baseUrl);
        this.transport = transport;
        this.executionEligibility = executionEligibility;
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    public boolean executionEnabled() {
        return false;
    }

    @Override
    public boolean registrationBindingSupported() {
        return true;
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        if (!executionEligibility.test(this)) {
            throw new IllegalStateException("KOGAS collector is not eligible for execution.");
        }
        validateRange(startDate, endDate);
        URI listUri = baseUri.resolve(LIST_PATH);
        Response listResponse = execute(listUri);
        List<ListNotice> notices = parseList(listResponse, listUri);

        Map<NoticeKey, ListNotice> uniqueNotices = new LinkedHashMap<>();
        for (ListNotice notice : notices) {
            if (!isServiceNotice(notice) || !isWithinRequestedPeriod(notice.publishedDate(), startDate, endDate)) {
                continue;
            }
            uniqueNotices.putIfAbsent(notice.key(), notice);
        }

        List<BidQualificationDto> candidates = new ArrayList<>();
        for (ListNotice notice : uniqueNotices.values()) {
            try {
                Response detailResponse = execute(notice.detailUri());
                candidates.add(toQualification(notice, parseDetail(detailResponse, notice.detailUri())));
            } catch (RuntimeException exception) {
                log.warn("KOGAS detail collection failed: sourceNoticeId={}, revision={}, errorType={}",
                        notice.key().sourceNoticeId(), notice.round(), exception.getClass().getSimpleName());
            }
        }
        return List.copyOf(candidates);
    }

    List<ListNotice> parseList(Response response, URI listUri) {
        requireSuccess(response, "KOGAS 목록");
        Document document = parseHtml(response, listUri);
        Elements tables = document.select("table");
        if (tables.isEmpty()) {
            throw new IllegalStateException("KOGAS 목록 표를 찾을 수 없습니다.");
        }

        List<ListNotice> notices = new ArrayList<>();
        boolean recognizedTable = false;
        for (Element table : tables) {
            Map<String, Integer> headings = headings(table);
            if (!hasHeading(headings, "공고명") || !hasHeading(headings, "공고번호", "공고코드")) {
                continue;
            }
            recognizedTable = true;
            for (Element row : table.select("tr")) {
                Elements cells = row.select("td");
                if (cells.isEmpty()) {
                    continue;
                }
                try {
                    ListNotice notice = parseListRow(cells, headings, listUri);
                    notices.add(notice);
                } catch (RuntimeException exception) {
                    log.warn("Malformed KOGAS list row skipped: errorType={}", exception.getClass().getSimpleName());
                }
            }
        }
        if (!recognizedTable) {
            throw new IllegalStateException("KOGAS 입찰공고 목록 구조를 확인할 수 없습니다.");
        }
        return List.copyOf(notices);
    }

    DetailData parseDetail(Response response, URI detailUri) {
        requireSuccess(response, "KOGAS 상세");
        Document document = parseHtml(response, detailUri);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element row : document.select("tr")) {
            Elements headers = row.select("th");
            Elements values = row.select("td");
            if (!headers.isEmpty() && !values.isEmpty()) {
                int count = Math.min(headers.size(), values.size());
                for (int i = 0; i < count; i++) {
                    fields.putIfAbsent(normalizeLabel(headers.get(i).text()), clean(values.get(i).text()));
                }
            }
        }
        for (Element term : document.select("dt")) {
            Element value = term.nextElementSibling();
            if (value != null && value.tagName().equals("dd")) {
                fields.putIfAbsent(normalizeLabel(term.text()), clean(value.text()));
            }
        }

        List<BidAttachmentDto> attachments = new ArrayList<>();
        Map<String, BidAttachmentDto> byUrl = new LinkedHashMap<>();
        for (Element link : document.select("a[href]")) {
            URI attachmentUri = resolvePublicAttachment(detailUri, link.attr("href"));
            if (attachmentUri == null) {
                continue;
            }
            String fileName = safeFileName(firstNonBlank(link.attr("download"), link.text()));
            BidAttachmentDto attachment = new BidAttachmentDto(
                    fileName, attachmentUri.toASCIIString(), classifyDocument(fileName), "NOT_ANALYZED"
            );
            byUrl.putIfAbsent(attachment.getFileUrl(), attachment);
        }
        attachments.addAll(byUrl.values());
        return new DetailData(Map.copyOf(fields), List.copyOf(attachments));
    }

    BidQualificationDto toQualification(ListNotice notice, DetailData detail) {
        BidQualificationDto value = new BidQualificationDto();
        value.setSourceCode(SOURCE_CODE);
        value.setSourceNoticeId(notice.key().sourceNoticeId());
        value.setRevision(notice.round());
        value.setDetailUrl(notice.detailUri().toASCIIString());
        value.setBidNtceDtlUrl(notice.detailUri().toASCIIString());
        value.setBidNtceNo(firstNonBlank(field(detail.fields(), "공고번호", "공고코드"), notice.noticeCode()));
        value.setBidNtceNm(firstNonBlank(field(detail.fields(), "공고명", "입찰공고명"), notice.title()));
        value.setNtceInsttNm(firstNonBlank(
                field(detail.fields(), "발주기관", "발주부서", "담당부서", "담당정보"), notice.orderingInformation()
        ));
        value.setBidNtceDt(normalizeDateTime(field(detail.fields(), "공고일시", "공고일", "게시일")));
        value.setBidClseDt(normalizeDateTime(firstNonBlank(
                field(detail.fields(), "입찰마감일시", "마감일시", "마감일"), notice.deadline()
        )));
        value.setBidOpeningDt(normalizeDateTime(field(detail.fields(), "개찰일시", "개찰일")));
        value.setContractMethod(field(detail.fields(), "계약방법"));
        value.setBidForm(field(detail.fields(), "입찰방법", "입찰방식"));
        value.setNoticeStatus(firstNonBlank(field(detail.fields(), "공고상태", "상태"), notice.status()));
        value.setAsignBdgtAmt(field(detail.fields(), "배정예산", "추정가격", "예정금액", "예산금액"));
        value.setAttachments(detail.attachments());
        value.setLicenseLimit(field(detail.fields(), "입찰참가자격", "참가자격", "면허제한"));
        value.setLicenseGroups(List.of());
        value.setParticipationRegion(field(detail.fields(), "지역제한", "참가지역"));
        value.setSucsfbidMthdNm(field(detail.fields(), "낙찰자결정방법", "낙찰방법"));
        value.setSucsfbidMthdAppStd(field(detail.fields(), "적격심사기준", "낙찰자결정기준"));
        value.setPqEvalYn(field(detail.fields(), "PQ심사여부", "PQ심사"));
        value.setTpEvalYn(field(detail.fields(), "TP심사여부", "TP심사"));
        value.setCmmnSpldmdAgrmntRcptdocMethd(field(detail.fields(), "공동수급협정서접수방식", "공동수급"));
        return value;
    }

    private ListNotice parseListRow(Elements cells, Map<String, Integer> headings, URI listUri) {
        Element titleCell = cell(cells, headings, "공고명", "입찰공고명");
        Element link = titleCell.selectFirst("a[href]");
        if (link == null) {
            throw new IllegalArgumentException("상세 링크가 없습니다.");
        }
        URI detailUri = resolveDetailUri(listUri, link.attr("href"));
        Map<String, String> parameters = queryParameters(detailUri);
        addNamedParameters(parameters, link.attr("onclick"));
        addNamedParameters(parameters, link.attr("href"));

        String noticeCode = required(firstNonBlank(
                parameters.get("notice_code"), text(cells, headings, "공고번호", "공고코드")
        ), "notice_code");
        String bidCode = required(parameters.get("bid_code"), "bid_code");
        String round = required(firstNonBlank(parameters.get("round"), text(cells, headings, "차수", "회차")), "round");
        return new ListNotice(
                noticeCode,
                bidCode,
                round,
                required(clean(titleCell.text()), "공고명"),
                text(cells, headings, "발주기관", "발주부서", "담당부서", "담당정보"),
                text(cells, headings, "마감일시", "마감일", "입찰마감일시"),
                text(cells, headings, "공고상태", "상태"),
                text(cells, headings, "공고구분", "입찰구분", "업무구분"),
                parseLocalDate(text(cells, headings, "공고일", "게시일")),
                detailUri
        );
    }

    private URI resolveDetailUri(URI listUri, String href) {
        URI uri = resolveHttpUri(listUri, href);
        if (uri == null || !sameKogasHost(uri) || !DETAIL_PATH.equals(uri.getPath())) {
            throw new IllegalArgumentException("허용되지 않은 KOGAS 상세 링크입니다.");
        }
        return uri;
    }

    private URI resolvePublicAttachment(URI detailUri, String href) {
        URI uri = resolveHttpUri(detailUri, href);
        if (uri == null || !sameKogasHost(uri) || !ATTACHMENT_PATH.equals(uri.getPath())) {
            return null;
        }
        return uri;
    }

    private URI resolveHttpUri(URI parent, String href) {
        if (href == null || href.isBlank() || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            return null;
        }
        try {
            URI resolved = parent.resolve(new URI(href.trim())).normalize();
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http")) && resolved.getRawUserInfo() == null
                    ? resolved : null;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean sameKogasHost(URI uri) {
        return uri.getHost() != null
                && uri.getHost().equalsIgnoreCase(baseUri.getHost())
                && effectivePort(uri) == effectivePort(baseUri);
    }

    private Response execute(URI uri) {
        if (!sameKogasHost(uri)) {
            throw new IllegalArgumentException("KOGAS 허용 host가 아닌 URL입니다.");
        }
        try {
            return transport.get(uri);
        } catch (IOException exception) {
            throw new IllegalStateException("KOGAS 공개 페이지 통신에 실패했습니다.", exception);
        }
    }

    private Document parseHtml(Response response, URI uri) {
        Charset charset = responseCharset(response);
        return Jsoup.parse(new String(response.body(), charset), uri.toASCIIString());
    }

    private Charset responseCharset(Response response) {
        String contentType = response.firstHeader("content-type");
        Charset headerCharset = findCharset(contentType);
        if (headerCharset != null) {
            return headerCharset;
        }
        int prefixLength = Math.min(response.body().length, 4096);
        String asciiPrefix = new String(response.body(), 0, prefixLength, StandardCharsets.ISO_8859_1);
        Charset metaCharset = findCharset(asciiPrefix);
        return metaCharset == null ? Charset.forName("EUC-KR") : metaCharset;
    }

    private Charset findCharset(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CHARSET_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Integer> headings(Element table) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Element headerRow = table.selectFirst("tr:has(th)");
        if (headerRow == null) {
            return result;
        }
        Elements headers = headerRow.select("th");
        for (int i = 0; i < headers.size(); i++) {
            result.putIfAbsent(normalizeLabel(headers.get(i).text()), i);
        }
        return result;
    }

    private boolean hasHeading(Map<String, Integer> headings, String... names) {
        for (String name : names) {
            if (headings.containsKey(normalizeLabel(name))) {
                return true;
            }
        }
        return false;
    }

    private Element cell(Elements cells, Map<String, Integer> headings, String... names) {
        for (String name : names) {
            Integer index = headings.get(normalizeLabel(name));
            if (index != null && index < cells.size()) {
                return cells.get(index);
            }
        }
        throw new IllegalArgumentException("필수 목록 열이 없습니다.");
    }

    private String text(Elements cells, Map<String, Integer> headings, String... names) {
        try {
            return clean(cell(cells, headings, names).text());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String field(Map<String, String> fields, String... names) {
        for (String name : names) {
            String value = fields.get(normalizeLabel(name));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return result;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]).toLowerCase(Locale.ROOT);
            String value = parts.length == 1 ? "" : decode(parts[1]);
            result.putIfAbsent(name, value);
        }
        return result;
    }

    private void addNamedParameters(Map<String, String> target, String source) {
        Matcher matcher = NAMED_PARAMETER_PATTERN.matcher(source == null ? "" : source);
        while (matcher.find()) {
            target.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), decode(matcher.group(2)));
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean isServiceNotice(ListNotice notice) {
        String category = clean(notice.category());
        if (category != null) {
            return category.contains("용역") || category.contains("서비스");
        }
        return notice.title().contains("용역") || notice.title().contains("서비스");
    }

    private boolean isWithinRequestedPeriod(LocalDate publishedDate, LocalDate startDate, LocalDate endDate) {
        return publishedDate == null || (!publishedDate.isBefore(startDate) && !publishedDate.isAfter(endDate));
    }

    private String normalizeDateTime(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replace("년", ".").replace("월", ".").replace("일", "").trim();
        for (DateTimeFormatter formatter : SOURCE_DATE_TIMES) {
            try {
                return LocalDateTime.parse(normalized, formatter).format(TARGET_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // Try the next public-page format.
            }
        }
        for (DateTimeFormatter formatter : SOURCE_DATES) {
            try {
                return LocalDate.parse(normalized, formatter).atStartOfDay().format(TARGET_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // Preserve an unrecognized public value instead of guessing.
            }
        }
        return normalized;
    }

    private LocalDate parseLocalDate(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        for (DateTimeFormatter formatter : SOURCE_DATES) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // Optional list value; leave it unavailable if the format is unknown.
            }
        }
        return null;
    }

    private String classifyDocument(String fileName) {
        if (fileName == null) {
            return "기타";
        }
        if (fileName.contains("공고")) {
            return "공고문";
        }
        if (fileName.contains("제안요청")) {
            return "제안요청서";
        }
        if (fileName.contains("과업")) {
            return "과업지시서";
        }
        return "기타";
    }

    private String safeFileName(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return "";
        }
        cleaned = cleaned.replace('/', '_').replace('\\', '_')
                .replaceAll("[\\p{Cntrl}]", "").strip();
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "";
        }
        return cleaned.substring(0, Math.min(cleaned.length(), 255));
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String normalizeLabel(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned.replaceAll("[\\s:*]", "");
    }

    private String firstNonBlank(String first, String second) {
        return clean(first) != null ? clean(first) : clean(second);
    }

    private String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return cleaned;
    }

    private void requireSuccess(Response response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " 응답이 실패했습니다. HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException(operation + " 응답 크기 제한을 초과했습니다.");
        }
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("KOGAS 공고 조회 시작일과 종료일을 확인해 주세요.");
        }
    }

    private static URI validateBaseUri(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("KOGAS 기본 URL이 올바르지 않습니다.");
            }
            return uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")
                    ? uri.resolve("/") : new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("KOGAS 기본 URL이 올바르지 않습니다.", exception);
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    record NoticeKey(String sourceNoticeId, String revision) {
    }

    record ListNotice(
            String noticeCode,
            String bidCode,
            String round,
            String title,
            String orderingInformation,
            String deadline,
            String status,
            String category,
            LocalDate publishedDate,
            URI detailUri
    ) {
        NoticeKey key() {
            return new NoticeKey(noticeCode + ":" + bidCode, round);
        }
    }

    record DetailData(Map<String, String> fields, List<BidAttachmentDto> attachments) {
    }

    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        Response {
            headers = headers == null ? Map.of() : headers;
            body = body == null ? new byte[0] : body;
        }

        String firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    interface Transport {
        Response get(URI uri) throws IOException;
    }

    private static final class SafeHttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Response get(URI initialUri) throws IOException {
            URI current = initialUri;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateKogasUri(current);
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "BizAssist-KOGAS-FixtureValidated/1.0")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET()
                        .build();
                HttpResponse<InputStream> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("KOGAS 요청이 중단되었습니다.", exception);
                }
                try (InputStream input = response.body()) {
                    if (REDIRECT_STATUSES.contains(response.statusCode())) {
                        if (redirects == MAX_REDIRECTS) {
                            throw new IOException("KOGAS redirect 제한을 초과했습니다.");
                        }
                        String location = response.headers().firstValue("location")
                                .orElseThrow(() -> new IOException("KOGAS redirect 위치가 없습니다."));
                        current = current.resolve(location);
                        continue;
                    }
                    long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
                    if (contentLength > MAX_RESPONSE_BYTES) {
                        throw new IOException("KOGAS 응답 크기 제한을 초과했습니다.");
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    for (int read; (read = input.read(buffer)) >= 0; ) {
                        total += read;
                        if (total > MAX_RESPONSE_BYTES) {
                            throw new IOException("KOGAS 응답 크기 제한을 초과했습니다.");
                        }
                        output.write(buffer, 0, read);
                    }
                    return new Response(response.statusCode(), response.headers().map(), output.toByteArray());
                }
            }
            throw new IOException("KOGAS redirect 제한을 초과했습니다.");
        }

        private void validateKogasUri(URI uri) throws IOException {
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !uri.getHost().equalsIgnoreCase("bid.kogas.or.kr")
                    || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 9443 && uri.getPort() != 443)) {
                throw new IOException("KOGAS host allowlist에 없는 URL입니다.");
            }
        }
    }
}
