package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.registration.BidSourceExecutionEligibilityService;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
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
import java.net.URLEncoder;
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
import java.util.HashSet;
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
    static final String BID_SALE_DETAIL_PATH = "/supplier/contents/bid/bid_detail_view_bidsale.jsp";
    static final String HD_DETAIL_PATH = "/supplier/contents/bid/bid_detail_view_hd.jsp";
    static final String ATTACHMENT_PATH = "/supplier/bid/bid_download_attfile.jsp";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_LIST_PAGES = 200;
    private static final int MAX_DIAGNOSTIC_TEXT_LENGTH = 120;
    private static final int MAX_DIAGNOSTIC_TABLES = 10;
    private static final int MAX_DIAGNOSTIC_HEADERS = 20;
    private static final int MAX_DIAGNOSTIC_FORMS = 10;
    private static final int MAX_DIAGNOSTIC_ROWS = 5;
    private static final int MAX_DIAGNOSTIC_CELLS = 12;
    private static final int MAX_DIAGNOSTIC_ELEMENTS = 12;
    private static final int MAX_DIAGNOSTIC_CELL_TEXT_LENGTH = 100;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> ALLOWED_DETAIL_POST_PATHS = Set.of(
            DETAIL_PATH, BID_SALE_DETAIL_PATH, HD_DETAIL_PATH
    );
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)(?:charset\\s*=\\s*|<meta[^>]+charset\\s*=\\s*[\\\"']?)([a-zA-Z0-9._-]+)"
    );
    private static final Pattern NAMED_PARAMETER_PATTERN = Pattern.compile(
            "(?i)(notice_code|bid_code|round)\\s*[=:,]\\s*[\\\"']?([^\\\"'&,)\\s]+)"
    );
    private static final Pattern JAVASCRIPT_CALL_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:javascript\\s*:\\s*)?(?:return\\s+)?"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*\\((.*)\\)\\s*;?\\s*$"
    );
    private static final Pattern VIEW_BID_FUNCTION_PATTERN = Pattern.compile(
            "(?i)function\\s+viewBid\\s*\\(([^)]*)\\)\\s*\\{"
    );
    private static final Pattern JAVASCRIPT_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LIST_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(20\\d{2}[./-]\\d{2}[./-]\\d{2})(?!\\d)"
    );
    private static final Pattern LIST_PAGE_PATTERN = Pattern.compile(
            "(?i)Total\\s+Records\\s*:\\s*[\\d,]+\\s+Pages\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)"
    );
    private static final Pattern LICENSE_CODE_PATTERN = Pattern.compile("(?<!\\d)(6146|1468)(?!\\d)");
    private static final String SUPERVISION_LICENSE_NAME = "정보시스템 감리법인";
    private static final String COMPUTER_SERVICE_LICENSE_NAME = "소프트웨어사업자(컴퓨터관련서비스사업)";
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
    private final boolean configuredExecutionEnabled;

    @Autowired
    public KogasBidCollector(
            @Value("${bid-source.kogas.base-url:https://bid.kogas.or.kr:9443}") String baseUrl,
            @Value("${bid-source.kogas.enabled:false}") boolean configuredExecutionEnabled,
            BidSourceExecutionEligibilityService executionEligibility
    ) {
        this(baseUrl, new SafeHttpTransport(), executionEligibility::isEligible, configuredExecutionEnabled);
    }

    KogasBidCollector(String baseUrl, Transport transport) {
        this(baseUrl, transport, collector -> true, false);
    }

    KogasBidCollector(
            String baseUrl,
            Transport transport,
            Predicate<BidCandidateCollector> executionEligibility
    ) {
        this(baseUrl, transport, executionEligibility, false);
    }

    KogasBidCollector(
            String baseUrl,
            Transport transport,
            Predicate<BidCandidateCollector> executionEligibility,
            boolean configuredExecutionEnabled
    ) {
        this.baseUri = validateBaseUri(baseUrl);
        this.transport = transport;
        this.executionEligibility = executionEligibility;
        this.configuredExecutionEnabled = configuredExecutionEnabled;
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    @Override
    public boolean executionEnabled() {
        return configuredExecutionEnabled;
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
        List<ListNotice> notices = collectListNotices();

        Map<NoticeKey, ListNotice> uniqueNotices = new LinkedHashMap<>();
        for (ListNotice notice : notices) {
            if (!isPotentiallyRelevantNotice(notice)
                    || !isWithinRequestedPeriod(notice.publishedDate(), startDate, endDate)) {
                continue;
            }
            uniqueNotices.putIfAbsent(notice.key(), notice);
        }

        List<BidQualificationDto> candidates = new ArrayList<>();
        for (ListNotice notice : uniqueNotices.values()) {
            try {
                Response detailResponse = notice.detailFormFields().isEmpty()
                        ? execute(notice.detailUri())
                        : executePost(notice.detailUri(), notice.detailFormFields());
                BidQualificationDto candidate = toQualification(
                        notice, parseDetail(detailResponse, notice.detailUri())
                );
                if (isWithinRequestedPeriod(
                        parsePublishedDate(candidate.getBidNtceDt()), startDate, endDate
                )) {
                    candidates.add(candidate);
                }
            } catch (RuntimeException exception) {
                log.warn("KOGAS detail collection failed: sourceNoticeId={}, revision={}, errorType={}",
                        notice.key().sourceNoticeId(), notice.round(), exception.getClass().getSimpleName());
            }
        }
        return List.copyOf(candidates);
    }

    private List<ListNotice> collectListNotices() {
        List<ListNotice> notices = new ArrayList<>();
        Set<List<NoticeKey>> seenPageIdentities = new HashSet<>();
        int totalPages = 1;
        for (int pageNumber = 1; pageNumber <= totalPages; pageNumber++) {
            URI listUri = pageNumber == 1 ? baseUri.resolve(LIST_PATH) : createListPageUri(pageNumber);
            ListPage page = parseListPage(execute(listUri), listUri, pageNumber == 1);
            if (page.pageNumber() != pageNumber) {
                throw new IllegalStateException("KOGAS pagination returned an unexpected page.");
            }
            if (pageNumber == 1) {
                totalPages = page.totalPages();
                if (totalPages > MAX_LIST_PAGES) {
                    throw new IllegalStateException("KOGAS pagination exceeds the safe page limit.");
                }
            } else if (page.totalPages() != totalPages) {
                throw new IllegalStateException("KOGAS pagination changed while collecting.");
            }

            List<NoticeKey> pageIdentity = page.notices().stream().map(ListNotice::key).distinct().toList();
            if (!seenPageIdentities.add(pageIdentity)) {
                throw new IllegalStateException("KOGAS pagination returned the same page repeatedly.");
            }
            if (pageNumber < totalPages && pageIdentity.isEmpty()) {
                throw new IllegalStateException("KOGAS pagination returned an empty page before the end.");
            }
            notices.addAll(page.notices());
        }
        return List.copyOf(notices);
    }

    private URI createListPageUri(int pageNumber) {
        return baseUri.resolve(LIST_PATH + "?page=" + pageNumber
                + "&worktype=&title=&e_startday=&e_endday=&o_startday=&o_endday=&orderplace=&reqbidno=");
    }

    List<ListNotice> parseList(Response response, URI listUri) {
        return parseListPage(response, listUri, true).notices();
    }

    private ListPage parseListPage(Response response, URI listUri, boolean diagnostics) {
        requireSuccess(response, "KOGAS 목록");
        Document document = parseHtml(response, listUri);
        Elements tables = document.select("table");
        if (diagnostics) {
            logListStructure(response, listUri, document, tables);
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("KOGAS 목록 표를 찾을 수 없습니다.");
        }

        List<ListNotice> notices = new ArrayList<>();
        boolean recognizedTable = false;
        for (Element table : tables) {
            Map<String, Integer> headings = headings(table);
            if (!hasHeading(headings, "공고명", "입찰공고명", "입찰명")
                    || !hasHeading(headings, "공고번호", "공고코드", "입찰번호")) {
                for (Element row : table.select("tr")) {
                    ListNotice notice = parseUnheadedListRow(directCells(row, "td"), listUri);
                    if (notice != null) {
                        recognizedTable = true;
                        notices.add(notice);
                    }
                }
                continue;
            }
            recognizedTable = true;
            for (Element row : table.select("tr")) {
                Elements cells = directCells(row, "td");
                if (cells.size() != headings.size() || isListHeaderRow(cells)) {
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
        PageMetadata pageMetadata = pageMetadata(document);
        return new ListPage(List.copyOf(notices), pageMetadata.pageNumber(), pageMetadata.totalPages());
    }

    private PageMetadata pageMetadata(Document document) {
        Matcher matcher = LIST_PAGE_PATTERN.matcher(document.text());
        if (!matcher.find()) {
            return new PageMetadata(1, 1);
        }
        int pageNumber = Integer.parseInt(matcher.group(1));
        int totalPages = Integer.parseInt(matcher.group(2));
        if (pageNumber < 1 || totalPages < pageNumber) {
            throw new IllegalStateException("KOGAS pagination metadata is invalid.");
        }
        return new PageMetadata(pageNumber, totalPages);
    }

    private ListNotice parseUnheadedListRow(Elements cells, URI listUri) {
        if (cells.isEmpty()) {
            return null;
        }
        for (Element link : cells.select("a[href], a[onclick]")) {
            URI linkedDetailUri = resolveHttpUri(listUri, link.attr("href"));
            Map<String, String> parameters = linkedDetailUri == null
                    ? new LinkedHashMap<>()
                    : queryParameters(linkedDetailUri);
            addNamedParameters(parameters, link.attr("onclick"));
            addNamedParameters(parameters, link.attr("href"));
            String noticeCode = clean(parameters.get("notice_code"));
            String bidCode = clean(parameters.get("bid_code"));
            String title = clean(link.text());
            if (noticeCode == null || bidCode == null || title == null) {
                continue;
            }
            String round = clean(parameters.get("round"));
            URI detailUri = isAllowedDetailUri(linkedDetailUri)
                    ? linkedDetailUri
                    : createDetailUri(noticeCode, bidCode, round);
                return new ListNotice(
                    noticeCode,
                    bidCode,
                    round,
                    title,
                    null,
                    null,
                    null,
                    findCategory(cells),
                    null,
                    null,
                    null,
                    findPublishedDate(cells),
                    detailUri,
                    Map.of()
            );
        }
        return null;
    }

    private String findCategory(Elements cells) {
        for (Element cell : cells) {
            String value = clean(cell.text());
            if (value != null && (value.contains("용역") || value.contains("서비스")
                    || value.contains("물품") || value.contains("공사"))) {
                return value;
            }
        }
        return null;
    }

    private LocalDate findPublishedDate(Elements cells) {
        for (Element cell : cells) {
            Matcher matcher = LIST_DATE_PATTERN.matcher(cell.text());
            if (matcher.find()) {
                LocalDate parsed = parseLocalDate(matcher.group(1));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private void logListStructure(Response response, URI requestedUri, Document document, Elements tables) {
        if (!log.isDebugEnabled()) {
            return;
        }
        URI responseUri = response.responseUri() == null ? requestedUri : response.responseUri();
        Elements forms = document.select("form");
        Element body = document.body();
        log.debug(
                "KOGAS list structure: status={}, contentType={}, responseHost={}, responsePath={}, "
                        + "title={}, tableCount={}, formCount={}, bodyId={}, bodyClasses={}",
                response.statusCode(),
                diagnosticText(response.firstHeader("content-type")),
                diagnosticText(responseUri == null ? null : responseUri.getHost()),
                diagnosticText(responseUri == null ? null : responseUri.getPath()),
                diagnosticText(document.title()),
                tables.size(),
                forms.size(),
                diagnosticText(body == null ? null : body.id()),
                diagnosticText(body == null ? null : body.className())
        );
        int tableCount = Math.min(tables.size(), MAX_DIAGNOSTIC_TABLES);
        for (int tableIndex = 0; tableIndex < tableCount; tableIndex++) {
            Element table = tables.get(tableIndex);
            List<String> headers = headerCells(table).stream()
                    .limit(MAX_DIAGNOSTIC_HEADERS)
                    .map(header -> diagnosticText(header.text()))
                    .toList();
            log.debug("KOGAS list table structure: tableIndex={}, tableId={}, tableClasses={}, headers={}",
                    tableIndex, diagnosticText(table.id()), diagnosticText(table.className()), headers);
        }
        int formCount = Math.min(forms.size(), MAX_DIAGNOSTIC_FORMS);
        for (int formIndex = 0; formIndex < formCount; formIndex++) {
            Element form = forms.get(formIndex);
            URI actionUri = safeDiagnosticUri(responseUri, form.attr("action"));
            log.debug(
                    "KOGAS list form structure: formIndex={}, formId={}, formClasses={}, actionHost={}, actionPath={}",
                    formIndex,
                    diagnosticText(form.id()),
                    diagnosticText(form.className()),
                    diagnosticText(actionUri == null ? null : actionUri.getHost()),
                    diagnosticText(actionUri == null ? null : actionUri.getPath())
            );
        }
    }

    private void logViewBidFunctionStructure(Document document, URI responseUri) {
        ViewBidFunction function = findViewBidFunction(document);
        if (function == null) {
            log.debug("KOGAS viewBid function structure: definitionFound=false");
            return;
        }

        List<FormFieldMapping> mappings = findFormFieldMappings(function, document);
        Element form = findMappedForm(document, mappings);
        String formIdentity = formIdentity(form);
        SubmitAnalysis submit = findSubmitAnalysis(function.body());
        ActionAnalysis actionAnalysis = findActionAnalysis(function);
        TypeRoutingAnalysis typeRouting = findTypeRoutingAnalysis(function);
        String assignedAction = findStaticFormAttribute(function.body(), formIdentity, "action");
        String action = firstNonBlank(assignedAction, form == null ? null : form.attr("action"));
        URI actionUri = safeDiagnosticUri(responseUri, action);
        String assignedMethod = findStaticFormAttribute(function.body(), formIdentity, "method");
        String method = firstNonBlank(assignedMethod, form == null ? null : form.attr("method"));
        log.debug(
                "KOGAS viewBid function structure: definitionFound=true, parameters={}, parameterCount={}, "
                        + "fieldMappings={}, parameterUsage={}, submitCalled={}, submitForm={}, formMethod={}, "
                        + "actionHost={}, actionPath={}, actionStaticPaths={}, actionParameters={}, "
                        + "typeBranches={}, defaultActionPaths={}",
                function.parameters(),
                function.parameters().size(),
                mappings.stream()
                        .map(mapping -> mapping.parameterName() + "->" + diagnosticText(mapping.fieldName()))
                        .toList(),
                parameterUsage(function, mappings, actionAnalysis),
                submit.called(),
                diagnosticText(submit.formIdentity()),
                diagnosticText(method == null ? "GET" : method.toUpperCase(Locale.ROOT)),
                diagnosticText(actionUri == null ? null : actionUri.getHost()),
                diagnosticText(actionUri == null ? null : actionUri.getPath()),
                actionAnalysis.staticPaths(),
                actionAnalysis.parameters(),
                typeRouting.branches().stream().map(TypeBranch::diagnosticValue).toList(),
                typeRouting.defaultActionPaths()
        );
    }

    private TypeRoutingAnalysis findTypeRoutingAnalysis(ViewBidFunction function) {
        if (!function.parameters().contains("type")) {
            return new TypeRoutingAnalysis(List.of(), List.of());
        }
        List<TypeConditionMatch> matches = new ArrayList<>();
        collectTypeConditionMatches(function.body(), Pattern.compile(
                "(?is)if\\s*\\(\\s*type\\s*(===|==|!==|!=)\\s*(['\"])(.*?)\\2\\s*\\)\\s*\\{"
        ), false, matches);
        collectTypeConditionMatches(function.body(), Pattern.compile(
                "(?is)if\\s*\\(\\s*(['\"])(.*?)\\1\\s*(===|==|!==|!=)\\s*type\\s*\\)\\s*\\{"
        ), true, matches);
        matches.sort(java.util.Comparator.comparingInt(TypeConditionMatch::startIndex));

        List<TypeBranch> branches = new ArrayList<>();
        List<SourceRange> conditionalRanges = new ArrayList<>();
        List<String> defaultPaths = new ArrayList<>();
        for (TypeConditionMatch condition : matches) {
            JavascriptBlock branchBlock = javascriptBlockRange(function.body(), condition.openingBraceIndex());
            if (branchBlock == null) {
                continue;
            }
            conditionalRanges.add(new SourceRange(condition.openingBraceIndex(), branchBlock.closingBraceIndex()));
            List<String> paths = findActionAnalysis(
                    new ViewBidFunction(function.parameters(), branchBlock.body())
            ).staticPaths();
            branches.add(new TypeBranch(condition.operator(), diagnosticText(condition.literal()), paths));

            Matcher elseMatcher = Pattern.compile("(?is)\\s*else\\s*(?!if\\b)\\{")
                    .matcher(function.body());
            elseMatcher.region(branchBlock.closingBraceIndex() + 1, function.body().length());
            if (elseMatcher.lookingAt()) {
                JavascriptBlock elseBlock = javascriptBlockRange(function.body(), elseMatcher.end() - 1);
                if (elseBlock != null) {
                    conditionalRanges.add(new SourceRange(elseMatcher.end() - 1, elseBlock.closingBraceIndex()));
                    mergeDistinct(defaultPaths, findActionAnalysis(
                            new ViewBidFunction(function.parameters(), elseBlock.body())
                    ).staticPaths());
                }
            }
        }

        StringBuilder outsideConditions = new StringBuilder(function.body());
        for (SourceRange range : conditionalRanges) {
            for (int index = range.startIndex(); index <= range.endIndex(); index++) {
                outsideConditions.setCharAt(index, ' ');
            }
        }
        mergeDistinct(defaultPaths, findActionAnalysis(
                new ViewBidFunction(function.parameters(), outsideConditions.toString())
        ).staticPaths());
        return new TypeRoutingAnalysis(List.copyOf(branches), List.copyOf(defaultPaths));
    }

    private void collectTypeConditionMatches(
            String body,
            Pattern pattern,
            boolean reversed,
            List<TypeConditionMatch> target
    ) {
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            String operator = reversed ? matcher.group(3) : matcher.group(1);
            String literal = reversed ? matcher.group(2) : matcher.group(3);
            boolean duplicate = target.stream().anyMatch(value -> value.startIndex() == matcher.start());
            if (!duplicate) {
                target.add(new TypeConditionMatch(
                        matcher.start(), matcher.end() - 1, operator, literal
                ));
            }
        }
    }

    private void mergeDistinct(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private Map<String, List<String>> parameterUsage(
            ViewBidFunction function,
            List<FormFieldMapping> mappings,
            ActionAnalysis actionAnalysis
    ) {
        Map<String, List<String>> usage = new LinkedHashMap<>();
        for (String parameter : function.parameters()) {
            List<String> locations = new ArrayList<>();
            if (mappings.stream().anyMatch(mapping -> mapping.parameterName().equals(parameter))) {
                locations.add("FIELD");
            }
            Pattern condition = Pattern.compile(
                    "(?is)(?:if|while)\\s*\\([^)]*\\b" + Pattern.quote(parameter) + "\\b[^)]*\\)"
            );
            if (condition.matcher(function.body()).find()) {
                locations.add("CONDITION");
            }
            if (actionAnalysis.parameters().contains(parameter)) {
                locations.add("ACTION");
            }
            usage.put(parameter, List.copyOf(locations));
        }
        return Map.copyOf(usage);
    }

    private SubmitAnalysis findSubmitAnalysis(String body) {
        Pattern directSubmit = Pattern.compile(
                "(?i)(?:document\\.)?([A-Za-z_$][\\w$]*)\\.submit\\s*\\(\\s*\\)"
        );
        Matcher directMatcher = directSubmit.matcher(body);
        if (directMatcher.find()) {
            return new SubmitAnalysis(true, directMatcher.group(1));
        }
        Pattern formsSubmit = Pattern.compile(
                "(?i)document\\.forms\\s*\\[\\s*['\"]([^'\"]+)['\"]\\s*]"
                        + "\\.submit\\s*\\(\\s*\\)"
        );
        Matcher formsMatcher = formsSubmit.matcher(body);
        return formsMatcher.find()
                ? new SubmitAnalysis(true, formsMatcher.group(1))
                : new SubmitAnalysis(false, null);
    }

    private ActionAnalysis findActionAnalysis(ViewBidFunction function) {
        Pattern actionAssignment = Pattern.compile(
                "(?is)(?:document\\.)?([A-Za-z_$][\\w$]*)\\.action\\s*=\\s*([^;]+);"
        );
        Matcher matcher = actionAssignment.matcher(function.body());
        List<String> staticPaths = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        while (matcher.find()) {
            String expression = matcher.group(2);
            Matcher stringMatcher = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(expression);
            while (stringMatcher.find()) {
                URI uri = safeDiagnosticUri(baseUri, stringMatcher.group(1));
                String path = uri == null ? null : clean(uri.getPath());
                if (path != null && (path.contains("/") || path.toLowerCase(Locale.ROOT).contains(".jsp"))) {
                    String diagnosticPath = diagnosticText(path);
                    if (!staticPaths.contains(diagnosticPath)) {
                        staticPaths.add(diagnosticPath);
                    }
                }
            }
            for (String parameter : function.parameters()) {
                if (!parameters.contains(parameter)
                        && Pattern.compile("\\b" + Pattern.quote(parameter) + "\\b")
                        .matcher(expression).find()) {
                    parameters.add(parameter);
                }
            }
        }
        return new ActionAnalysis(List.copyOf(staticPaths), List.copyOf(parameters));
    }

    private String findStaticFormAttribute(String body, String formIdentity, String attribute) {
        if (formIdentity == null) {
            return null;
        }
        Pattern assignment = Pattern.compile(
                "(?i)(?:document\\.)?" + Pattern.quote(formIdentity) + "\\."
                        + Pattern.quote(attribute) + "\\s*=\\s*['\"]([^'\"]+)['\"]\\s*;"
        );
        Matcher matcher = assignment.matcher(body);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    private ViewBidFunction findViewBidFunction(Document document) {
        for (Element script : document.select("script:not([src])")) {
            String source = script.data();
            Matcher matcher = VIEW_BID_FUNCTION_PATTERN.matcher(source);
            if (!matcher.find()) {
                continue;
            }
            List<String> parameters = new ArrayList<>();
            for (String candidate : matcher.group(1).split(",")) {
                String parameter = candidate.trim();
                if (!parameter.isEmpty() && JAVASCRIPT_IDENTIFIER_PATTERN.matcher(parameter).matches()) {
                    parameters.add(parameter);
                }
            }
            String body = javascriptBlock(source, matcher.end() - 1);
            if (body != null) {
                return new ViewBidFunction(List.copyOf(parameters), body);
            }
        }
        return null;
    }

    private String javascriptBlock(String source, int openingBraceIndex) {
        JavascriptBlock block = javascriptBlockRange(source, openingBraceIndex);
        return block == null ? null : block.body();
    }

    private JavascriptBlock javascriptBlockRange(String source, int openingBraceIndex) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = openingBraceIndex; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return new JavascriptBlock(source.substring(openingBraceIndex + 1, index), index);
            }
        }
        return null;
    }

    private List<FormFieldMapping> findFormFieldMappings(ViewBidFunction function, Document document) {
        List<FormFieldMapping> mappings = new ArrayList<>();
        for (String parameter : function.parameters()) {
            FormFieldMapping mapping = findDirectFormFieldMapping(function.body(), parameter);
            if (mapping == null) {
                mapping = findElementIdMapping(function.body(), parameter, document);
            }
            if (mapping != null) {
                mappings.add(mapping);
            }
        }
        return List.copyOf(mappings);
    }

    private FormFieldMapping findDirectFormFieldMapping(String body, String parameter) {
        Pattern directAssignment = Pattern.compile(
                "(?i)(?:document\\.)?([A-Za-z_$][\\w$]*)\\.([A-Za-z_$][\\w$]*)"
                        + "\\.value\\s*=\\s*" + Pattern.quote(parameter) + "\\b"
        );
        Matcher directMatcher = directAssignment.matcher(body);
        if (directMatcher.find()) {
            return new FormFieldMapping(parameter, directMatcher.group(2), directMatcher.group(1));
        }

        Pattern formsAssignment = Pattern.compile(
                "(?i)document\\.forms\\s*\\[\\s*['\"]([^'\"]+)['\"]\\s*]\\s*"
                        + "(?:\\.elements\\s*\\[\\s*['\"]([^'\"]+)['\"]\\s*]|\\.([A-Za-z_$][\\w$]*))"
                        + "\\.value\\s*=\\s*" + Pattern.quote(parameter) + "\\b"
        );
        Matcher formsMatcher = formsAssignment.matcher(body);
        if (formsMatcher.find()) {
            return new FormFieldMapping(
                    parameter,
                    firstNonBlank(formsMatcher.group(2), formsMatcher.group(3)),
                    formsMatcher.group(1)
            );
        }
        return null;
    }

    private FormFieldMapping findElementIdMapping(String body, String parameter, Document document) {
        Pattern idAssignment = Pattern.compile(
                "(?i)document\\.getElementById\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)"
                        + "\\.value\\s*=\\s*" + Pattern.quote(parameter) + "\\b"
        );
        Matcher matcher = idAssignment.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String fieldId = matcher.group(1);
        Element field = document.getElementById(fieldId);
        Element form = field == null ? null : field.closest("form");
        return new FormFieldMapping(parameter, fieldId, formIdentity(form));
    }

    private Element findMappedForm(Document document, List<FormFieldMapping> mappings) {
        for (FormFieldMapping mapping : mappings) {
            for (Element form : document.select("form")) {
                String identity = formIdentity(form);
                if (identity != null && identity.equals(mapping.formIdentity())) {
                    return form;
                }
                for (Element field : form.select("input, button, select, textarea")) {
                    if (mapping.fieldName().equals(field.attr("name"))
                            || mapping.fieldName().equals(field.id())) {
                        return form;
                    }
                }
            }
        }
        return null;
    }

    private String formIdentity(Element form) {
        return form == null ? null : firstNonBlank(form.attr("name"), form.id());
    }

    private void logTableRows(int tableIndex, Element table) {
        Elements rows = table.select("tr");
        int rowCount = Math.min(rows.size(), MAX_DIAGNOSTIC_ROWS);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            Elements cells = diagnosticCells(rows.get(rowIndex));
            log.debug("KOGAS list row structure: tableIndex={}, rowIndex={}, cellCount={}",
                    tableIndex, rowIndex, cells.size());
            int cellCount = Math.min(cells.size(), MAX_DIAGNOSTIC_CELLS);
            for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
                Element cell = cells.get(cellIndex);
                log.debug(
                        "KOGAS list cell structure: tableIndex={}, rowIndex={}, cellIndex={}, "
                                + "tag={}, cellClasses={}, text={}",
                        tableIndex,
                        rowIndex,
                        cellIndex,
                        cell.tagName(),
                        diagnosticText(cell.className()),
                        diagnosticCellText(cell.text())
                );
                logCellElements(tableIndex, rowIndex, cellIndex, cell);
            }
        }
    }

    private Elements diagnosticCells(Element row) {
        Elements cells = new Elements();
        for (Element child : row.children()) {
            if (child.tagName().equals("th") || child.tagName().equals("td")) {
                cells.add(child);
            }
        }
        return cells;
    }

    private void logCellElements(int tableIndex, int rowIndex, int cellIndex, Element cell) {
        Elements elements = cell.select("a, button, input");
        int elementCount = Math.min(elements.size(), MAX_DIAGNOSTIC_ELEMENTS);
        for (int elementIndex = 0; elementIndex < elementCount; elementIndex++) {
            Element element = elements.get(elementIndex);
            JavascriptCall javascriptCall = javascriptCall(element);
            log.debug(
                    "KOGAS list cell element structure: tableIndex={}, rowIndex={}, cellIndex={}, "
                            + "elementIndex={}, tag={}, elementId={}, elementClasses={}, hasHref={}, "
                            + "hasAction={}, javascriptFunction={}, javascriptArgumentCount={}",
                    tableIndex,
                    rowIndex,
                    cellIndex,
                    elementIndex,
                    element.tagName(),
                    diagnosticText(element.id()),
                    diagnosticText(element.className()),
                    element.hasAttr("href"),
                    element.hasAttr("action") || element.hasAttr("formaction"),
                    javascriptCall == null ? "(none)" : javascriptCall.functionName(),
                    javascriptCall == null ? 0 : javascriptCall.argumentCount()
            );
        }
    }

    private JavascriptCall javascriptCall(Element element) {
        String script = clean(element.attr("onclick"));
        if (script == null) {
            String href = clean(element.attr("href"));
            if (href != null && href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                script = href;
            }
        }
        if (script == null) {
            return null;
        }
        Matcher matcher = JAVASCRIPT_CALL_PATTERN.matcher(script);
        if (!matcher.matches()) {
            return new JavascriptCall("(unrecognized)", -1);
        }
        return new JavascriptCall(matcher.group(1), countJavascriptArguments(matcher.group(2)));
    }

    private int countJavascriptArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return 0;
        }
        int count = 1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private String diagnosticCellText(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return "(none)";
        }
        return cleaned.length() <= MAX_DIAGNOSTIC_CELL_TEXT_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_DIAGNOSTIC_CELL_TEXT_LENGTH);
    }

    private String diagnosticText(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return "(none)";
        }
        return cleaned.length() <= MAX_DIAGNOSTIC_TEXT_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_DIAGNOSTIC_TEXT_LENGTH) + "...";
    }

    private URI safeDiagnosticUri(URI base, String value) {
        if (base == null || value == null || value.isBlank()) {
            return null;
        }
        try {
            URI resolved = base.resolve(value);
            return new URI(resolved.getScheme(), null, resolved.getHost(), resolved.getPort(),
                    resolved.getPath(), null, null);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return null;
        }
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
                    addDetailField(fields, headers.get(i).text(), values.get(i).text());
                }
            }
            Elements cells = directCells(row, "td");
            for (int i = 0; i + 1 < cells.size(); i++) {
                Element label = cells.get(i);
                Element value = cells.get(i + 1);
                if ((label.hasClass("t_g") || label.hasClass("t")) && value.hasClass("c")) {
                    addDetailField(fields, label.text(), value.text());
                    i++;
                }
            }
        }
        for (Element term : document.select("dt")) {
            Element value = term.nextElementSibling();
            if (value != null && value.tagName().equals("dd")) {
                addDetailField(fields, term.text(), value.text());
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

    private void addDetailField(Map<String, String> fields, String label, String value) {
        String normalizedLabel = normalizeLabel(label);
        String cleanedValue = clean(value);
        if (!normalizedLabel.isEmpty() && cleanedValue != null) {
            fields.putIfAbsent(normalizedLabel, cleanedValue);
        }
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
        String publishedDate = notice.publishedDate() == null ? null : notice.publishedDate().toString();
        value.setBidNtceDt(normalizeDateTime(firstNonBlank(
                field(detail.fields(), "공고일시", "공고일", "게시일"), publishedDate
        )));
        value.setBidClseDt(normalizeDateTime(firstNonBlank(
                field(detail.fields(), "입찰마감일시", "마감일시", "마감일"), notice.deadline()
        )));
        value.setBidOpeningDt(normalizeDateTime(firstNonBlank(
                field(detail.fields(), "개찰일시", "개찰일"), notice.openingDate()
        )));
        String detailContractMethod = field(detail.fields(), "계약방법");
        String contractMethod = firstNonBlank(detailContractMethod, notice.contractMethod());
        value.setContractMethod(contractMethod);
        value.setBidForm(firstNonBlank(field(detail.fields(), "입찰방법", "입찰방식"), notice.bidForm()));
        value.setNoticeStatus(firstNonBlank(field(detail.fields(), "공고상태", "상태"), notice.status()));
        value.setAsignBdgtAmt(field(detail.fields(), "배정예산", "추정가격", "예정금액", "예산금액"));
        value.setAttachments(detail.attachments());
        String licenseLimit = field(
                detail.fields(), "입찰참가자격", "참가자격", "면허제한", "면허사항제한"
        );
        value.setLicenseLimit(licenseLimit);
        value.setLicenseGroups(parsePublishedLicenseGroups(detail.fields(), licenseLimit));
        value.setParticipationRegion(normalizeParticipationRegion(
                field(detail.fields(), "지역제한", "참가지역")
        ));
        String awardMethod = field(
                detail.fields(), "2. 낙찰자결정방법", "낙찰자결정방법", "낙찰방법"
        );
        value.setSucsfbidMthdNm(isReferencedInNotice(awardMethod) ? detailContractMethod : awardMethod);
        value.setSucsfbidMthdAppStd(field(detail.fields(), "적격심사기준", "낙찰자결정기준"));
        value.setArsltCmptYn(field(detail.fields(), "실적경쟁여부", "실적경쟁"));
        value.setPqEvalYn(field(detail.fields(), "PQ심사여부", "PQ심사"));
        value.setTpEvalYn(field(detail.fields(), "TP심사여부", "TP심사"));
        value.setCmmnSpldmdAgrmntRcptdocMethd(field(
                detail.fields(), "공동수급협정서접수방식", "공동수급", "도급형태"
        ));
        return value;
    }

    private List<LicenseRequirementGroup> parsePublishedLicenseGroups(
            Map<String, String> fields,
            String licenseLimit
    ) {
        List<LicenseRequirementGroup> groups = new ArrayList<>();
        boolean publishedGroupFieldPresent = false;
        for (int groupNumber = 1; groupNumber <= 4; groupNumber++) {
            String sourceText = field(fields, "업종그룹" + groupNumber);
            publishedGroupFieldPresent |= sourceText != null;
            List<LicenseRequirement> requirements = parseLicenseRequirements(sourceText);
            if (!requirements.isEmpty()) {
                groups.add(new LicenseRequirementGroup(Integer.toString(groupNumber), requirements));
            }
        }
        if (!groups.isEmpty()) {
            return List.copyOf(groups);
        }
        return publishedGroupFieldPresent ? List.of() : parsePublishedLicenseCodes(licenseLimit);
    }

    private List<LicenseRequirementGroup> parsePublishedLicenseCodes(String licenseLimit) {
        List<LicenseRequirement> requirements = parseLicenseRequirements(licenseLimit);
        return requirements.isEmpty()
                ? List.of()
                : List.of(new LicenseRequirementGroup("1", requirements));
    }

    private List<LicenseRequirement> parseLicenseRequirements(String sourceText) {
        String normalized = clean(sourceText);
        if (normalized == null || normalized.equals("-")) {
            return List.of();
        }
        LinkedHashMap<String, String> codes = new LinkedHashMap<>();
        Matcher matcher = LICENSE_CODE_PATTERN.matcher(normalized);
        while (matcher.find()) {
            codes.putIfAbsent(matcher.group(1), normalized);
        }
        if (normalized.contains(SUPERVISION_LICENSE_NAME)) {
            codes.putIfAbsent("6146", normalized);
        }
        if (normalized.contains(COMPUTER_SERVICE_LICENSE_NAME)) {
            codes.putIfAbsent("1468", normalized);
        }
        if (codes.isEmpty()) {
            return List.of(new LicenseRequirement("1", "", normalized, normalized));
        }
        List<LicenseRequirement> requirements = new ArrayList<>();
        for (Map.Entry<String, String> code : codes.entrySet()) {
            requirements.add(new LicenseRequirement(
                    Integer.toString(requirements.size() + 1), code.getKey(), licenseName(code.getKey()), code.getValue()
            ));
        }
        return List.copyOf(requirements);
    }

    private String licenseName(String code) {
        return switch (code) {
            case "6146" -> SUPERVISION_LICENSE_NAME;
            case "1468" -> COMPUTER_SERVICE_LICENSE_NAME;
            default -> "";
        };
    }

    private String normalizeParticipationRegion(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        return normalized.startsWith("전국대상") || normalized.equals("전국") ? "제한없음" : normalized;
    }

    private boolean isReferencedInNotice(String value) {
        String normalized = clean(value);
        return normalized == null || normalized.contains("입찰공고문 참조");
    }

    private ListNotice parseListRow(Elements cells, Map<String, Integer> headings, URI listUri) {
        Element titleCell = cell(cells, headings, "공고명", "입찰공고명", "입찰명");
        Element link = titleCell.selectFirst("a[href], a[onclick]");
        if (link == null) {
            throw new IllegalArgumentException("상세 링크가 없습니다.");
        }
        URI linkedDetailUri = resolveHttpUri(listUri, link.attr("href"));
        Map<String, String> parameters = linkedDetailUri == null
                ? new LinkedHashMap<>()
                : queryParameters(linkedDetailUri);
        addNamedParameters(parameters, link.attr("onclick"));
        addNamedParameters(parameters, link.attr("href"));
        Element numberLink = cell(cells, headings, "공고번호", "공고코드", "입찰번호")
                .selectFirst("a[href], a[onclick]");
        if (numberLink != null && numberLink != link) {
            addNamedParameters(parameters, numberLink.attr("onclick"));
            addNamedParameters(parameters, numberLink.attr("href"));
        }

        ViewBidCall viewBidCall = parseViewBidCall(link);
        if (viewBidCall == null && numberLink != null) {
            viewBidCall = parseViewBidCall(numberLink);
        }

        String noticeCode = required(firstNonBlank(
                parameters.get("notice_code"),
                viewBidCall == null ? text(cells, headings, "공고번호", "공고코드", "입찰번호")
                        : viewBidCall.noticeCode()
        ), "notice_code");
        String bidCode = required(firstNonBlank(
                parameters.get("bid_code"), viewBidCall == null ? null : viewBidCall.bidCode()
        ), "bid_code");
        String round = firstNonBlank(
                parameters.get("round"),
                viewBidCall == null ? text(cells, headings, "차수", "회차") : viewBidCall.round()
        );
        Map<String, String> detailFormFields = Map.of();
        URI detailUri;
        if (isAllowedDetailUri(linkedDetailUri)) {
            detailUri = linkedDetailUri;
        } else if (viewBidCall != null) {
            detailUri = baseUri.resolve(detailPathForType(viewBidCall.type()));
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("notice_code", noticeCode);
            fields.put("bid_code", bidCode);
            fields.put("round", round == null ? "" : round);
            detailFormFields = Map.copyOf(fields);
        } else {
            detailUri = createDetailUri(noticeCode, bidCode, round);
        }
        return new ListNotice(
                noticeCode,
                bidCode,
                round,
                required(clean(titleCell.text()), "공고명"),
                text(cells, headings, "발주기관", "발주부서", "담당부서", "담당정보"),
                text(cells, headings, "마감일시", "마감일", "입찰마감일시", "입찰신청및 입찰마감일시"),
                text(cells, headings, "공고상태", "상태", "취소 여부"),
                text(cells, headings, "업무구분", "공고구분"),
                text(cells, headings, "입찰구분"),
                text(cells, headings, "계약방법"),
                text(cells, headings, "개찰일시"),
                parseLocalDate(text(cells, headings, "공고일", "게시일")),
                detailUri,
                detailFormFields
        );
    }

    private ViewBidCall parseViewBidCall(Element link) {
        Matcher matcher = null;
        Pattern callPattern = Pattern.compile(
                "(?is)(?:javascript\\s*:\\s*)?viewBid\\s*\\((.*)\\)\\s*;?"
        );
        for (String source : List.of(link.attr("onclick"), link.attr("href"))) {
            Matcher candidate = callPattern.matcher(source == null ? "" : source.trim());
            if (candidate.matches()) {
                matcher = candidate;
                break;
            }
        }
        if (matcher == null) {
            return null;
        }
        List<String> arguments = parseQuotedJavascriptArguments(matcher.group(1));
        if (arguments.size() != 4) {
            throw new IllegalArgumentException("viewBid argument structure is invalid.");
        }
        return new ViewBidCall(
                required(arguments.get(0), "notice_code"),
                required(arguments.get(1), "bid_code"),
                clean(arguments.get(2)),
                clean(arguments.get(3))
        );
    }

    private List<String> parseQuotedJavascriptArguments(String source) {
        List<String> arguments = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            if (index >= source.length() || (source.charAt(index) != '\'' && source.charAt(index) != '"')) {
                throw new IllegalArgumentException("viewBid arguments must be quoted strings.");
            }
            char quote = source.charAt(index++);
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == quote) {
                    closed = true;
                    break;
                }
                if (current == '\\') {
                    if (index >= source.length()) {
                        throw new IllegalArgumentException("viewBid escape is incomplete.");
                    }
                    char escaped = source.charAt(index++);
                    if (escaped != '\\' && escaped != '\'' && escaped != '"') {
                        throw new IllegalArgumentException("viewBid escape is not allowed.");
                    }
                    current = escaped;
                }
                if (value.length() >= 256) {
                    throw new IllegalArgumentException("viewBid argument is too long.");
                }
                value.append(current);
            }
            if (!closed) {
                throw new IllegalArgumentException("viewBid argument is not closed.");
            }
            arguments.add(value.toString());
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            if (index == source.length()) {
                return List.copyOf(arguments);
            }
            if (source.charAt(index++) != ',') {
                throw new IllegalArgumentException("viewBid argument separator is invalid.");
            }
        }
        return List.copyOf(arguments);
    }

    private String detailPathForType(String type) {
        if ("S".equals(type)) {
            return BID_SALE_DETAIL_PATH;
        }
        if ("H".equals(type)) {
            return HD_DETAIL_PATH;
        }
        return DETAIL_PATH;
    }

    private boolean isAllowedDetailUri(URI uri) {
        return uri != null && sameKogasHost(uri) && DETAIL_PATH.equals(uri.getPath());
    }

    private URI createDetailUri(String noticeCode, String bidCode, String round) {
        StringBuilder query = new StringBuilder()
                .append("notice_code=").append(encode(noticeCode))
                .append("&bid_code=").append(encode(bidCode));
        if (clean(round) != null) {
            query.append("&round=").append(encode(round));
        }
        return URI.create(baseUri.resolve(DETAIL_PATH).toASCIIString() + "?" + query);
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

    private Response executePost(URI uri, Map<String, String> formFields) {
        if (!sameKogasHost(uri) || !ALLOWED_DETAIL_POST_PATHS.contains(uri.getPath())) {
            throw new IllegalArgumentException("KOGAS 허용 상세 URL이 아닙니다.");
        }
        if (!formFields.keySet().equals(Set.of("notice_code", "bid_code", "round"))) {
            throw new IllegalArgumentException("KOGAS 상세 POST field가 올바르지 않습니다.");
        }
        try {
            return transport.post(uri, formFields);
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
        Elements headers = headerCells(table);
        for (int i = 0; i < headers.size(); i++) {
            result.putIfAbsent(normalizeLabel(headers.get(i).text()), i);
        }
        return result;
    }

    private Elements headerCells(Element table) {
        for (Element row : table.select("tr")) {
            Elements thCells = directCells(row, "th");
            if (!thCells.isEmpty()) {
                return thCells;
            }
            Elements tdCells = directCells(row, "td");
            if (isListHeaderRow(tdCells)) {
                return tdCells;
            }
        }
        return new Elements();
    }

    private Elements directCells(Element row, String tagName) {
        Elements cells = new Elements();
        for (Element child : row.children()) {
            if (child.tagName().equals(tagName)) {
                cells.add(child);
            }
        }
        return cells;
    }

    private boolean isListHeaderRow(Elements cells) {
        Set<String> labels = cells.stream()
                .map(cell -> normalizeLabel(cell.text()))
                .collect(java.util.stream.Collectors.toSet());
        return (labels.contains(normalizeLabel("공고명"))
                || labels.contains(normalizeLabel("입찰공고명"))
                || labels.contains(normalizeLabel("입찰명")))
                && (labels.contains(normalizeLabel("공고번호"))
                || labels.contains(normalizeLabel("공고코드"))
                || labels.contains(normalizeLabel("입찰번호")));
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

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isPotentiallyRelevantNotice(ListNotice notice) {
        String category = clean(notice.category());
        if (category == null) {
            return true;
        }
        return category.contains("용역")
                || category.contains("서비스")
                || hasPotentialTitleSignal(notice.title());
    }

    private boolean hasPotentialTitleSignal(String title) {
        String normalized = clean(title);
        if (normalized == null) {
            return false;
        }
        normalized = normalized.replaceAll("\\s+", "");
        return normalized.contains("감리")
                || normalized.contains("개인정보")
                || normalized.contains("정보시스템");
    }

    private boolean isWithinRequestedPeriod(LocalDate publishedDate, LocalDate startDate, LocalDate endDate) {
        return publishedDate == null || (!publishedDate.isBefore(startDate) && !publishedDate.isAfter(endDate));
    }

    private String normalizeDateTime(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        String dateMeaning = normalized.replace("-", "").replaceAll("\\s+", "");
        if ("입찰마감후즉시".equals(dateMeaning) || "입찰마감후저장".equals(dateMeaning)) {
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

    private LocalDate parsePublishedDate(String value) {
        String normalized = clean(value);
        if (normalized == null) {
            return null;
        }
        for (DateTimeFormatter formatter : SOURCE_DATE_TIMES) {
            try {
                return LocalDateTime.parse(normalized, formatter).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // Try the next published-date format.
            }
        }
        return parseLocalDate(normalized);
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

    private record PageMetadata(int pageNumber, int totalPages) {
    }

    private record ListPage(List<ListNotice> notices, int pageNumber, int totalPages) {
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
            String bidForm,
            String contractMethod,
            String openingDate,
            LocalDate publishedDate,
            URI detailUri,
            Map<String, String> detailFormFields
    ) {
        ListNotice {
            detailFormFields = detailFormFields == null ? Map.of() : Map.copyOf(detailFormFields);
        }

        NoticeKey key() {
            return new NoticeKey(noticeCode + ":" + bidCode, round);
        }
    }

    record DetailData(Map<String, String> fields, List<BidAttachmentDto> attachments) {
    }

    private record JavascriptCall(String functionName, int argumentCount) {
    }

    private record ViewBidCall(String noticeCode, String bidCode, String round, String type) {
    }

    private record ViewBidFunction(List<String> parameters, String body) {
    }

    private record FormFieldMapping(String parameterName, String fieldName, String formIdentity) {
    }

    private record SubmitAnalysis(boolean called, String formIdentity) {
    }

    private record ActionAnalysis(List<String> staticPaths, List<String> parameters) {
    }

    private record TypeRoutingAnalysis(List<TypeBranch> branches, List<String> defaultActionPaths) {
    }

    private record TypeBranch(String operator, String literal, List<String> actionPaths) {
        String diagnosticValue() {
            return "{operator=" + operator + ", literal=" + literal + ", actionPaths=" + actionPaths + "}";
        }
    }

    private record TypeConditionMatch(
            int startIndex,
            int openingBraceIndex,
            String operator,
            String literal
    ) {
    }

    private record SourceRange(int startIndex, int endIndex) {
    }

    private record JavascriptBlock(String body, int closingBraceIndex) {
    }

    record Response(int statusCode, Map<String, List<String>> headers, byte[] body, URI responseUri) {
        Response {
            headers = headers == null ? Map.of() : headers;
            body = body == null ? new byte[0] : body;
        }

        Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
            this(statusCode, headers, body, null);
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

        default Response post(URI uri, Map<String, String> formFields) throws IOException {
            throw new IOException("KOGAS POST transport is not configured.");
        }
    }

    private static final class SafeHttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Response get(URI initialUri) throws IOException {
            return send(initialUri, null);
        }

        @Override
        public Response post(URI initialUri, Map<String, String> formFields) throws IOException {
            if (!ALLOWED_DETAIL_POST_PATHS.contains(initialUri.getPath())) {
                throw new IOException("KOGAS POST path allowlist에 없는 URL입니다.");
            }
            if (formFields == null || !formFields.keySet().equals(Set.of("notice_code", "bid_code", "round"))
                    || formFields.values().stream().anyMatch(value -> value == null || value.length() > 256)) {
                throw new IOException("KOGAS POST field가 올바르지 않습니다.");
            }
            return send(initialUri, formFields);
        }

        private Response send(URI initialUri, Map<String, String> initialFormFields) throws IOException {
            URI current = initialUri;
            Map<String, String> formFields = initialFormFields;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateKogasUri(current);
                if (formFields != null && !ALLOWED_DETAIL_POST_PATHS.contains(current.getPath())) {
                    throw new IOException("KOGAS POST redirect path가 허용되지 않습니다.");
                }
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(current)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "BizAssist-KOGAS-FixtureValidated/1.0")
                        .header("Accept", "text/html,application/xhtml+xml");
                if (formFields == null) {
                    requestBuilder.GET();
                } else {
                    requestBuilder
                            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody(formFields), StandardCharsets.UTF_8));
                }
                HttpRequest request = requestBuilder.build();
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
                        if (response.statusCode() == 303
                                || (formFields != null
                                && (response.statusCode() == 301 || response.statusCode() == 302))) {
                            formFields = null;
                        }
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
                    return new Response(response.statusCode(), response.headers().map(), output.toByteArray(), current);
                }
            }
            throw new IOException("KOGAS redirect 제한을 초과했습니다.");
        }

        private String formBody(Map<String, String> formFields) {
            return formFields.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
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
