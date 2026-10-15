package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.bid.license.LicenseNormalizer;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursor;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursorRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetry;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetryRepository;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.d2b.promotion.D2bCandidateEvidenceService;
import com.comhu.bidmonitor.bid.source.d2b.promotion.D2bCandidateObservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.classifier.BidRelevanceClassifier;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 방위사업청 공식 공공데이터 OpenAPI에서 D2B 입찰 후보를 수집한다. */
@Component
public class D2bBidCollector implements BidCandidateCollector {

    static final String SOURCE_CODE = "D2B";
    static final int PAGE_SIZE = 100;
    static final int MAX_REQUESTS_PER_COLLECTION = 100;
    static final String COVERAGE_SCOPE = "FULL_DATE";

    private static final Logger log = LoggerFactory.getLogger(D2bBidCollector.class);
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SAFE_RESULT_CODE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final List<String> SEARCH_TERMS = List.of(
            "정보시스템 감리", "정보화 감리", "개인정보 영향평가", "개인정보영향평가", "감리"
    );
    private final String baseUrl;
    private final String serviceKey;
    private final Transport transport;
    private final CallQuota callQuota;
    private final boolean configuredExecutionEnabled;
    private final BidSourceCollectionCursorRepository cursorRepository;
    private final BidSourceCollectionRetryRepository retryRepository;
    private final BidRelevanceClassifier relevanceClassifier;
    private final CoverageConfig coverageConfig;
    private final Clock clock;
    private final Consumer<List<D2bCandidateObservation>> evidenceSink;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, PendingCoverageCompletion> pendingCoverageCompletions =
            new ConcurrentHashMap<>();
    @Autowired
    public D2bBidCollector(
            @Value("${d2b.api.base-url}") String baseUrl,
            @Value("${d2b.api.service-key:}") String serviceKey,
            @Value("${bid-source.d2b.enabled:false}") boolean configuredExecutionEnabled,
            D2bDailyQuotaService dailyQuotaService,
            BidSourceCollectionCursorRepository cursorRepository,
            BidSourceCollectionRetryRepository retryRepository,
            D2bCandidateEvidenceService evidenceService,
            BidRelevanceClassifier relevanceClassifier,
            Clock clock,
            @Value("${d2b.api.fast-path-max-calls:40}") int fastPathMaxCalls,
            @Value("${d2b.api.coverage-list-max-calls:8}") int coverageListMaxCalls,
            @Value("${d2b.api.coverage-detail-max-calls:40}") int coverageDetailMaxCalls,
            @Value("${d2b.api.coverage-page-size:10}") int coveragePageSize,
            @Value("${d2b.api.coverage-enabled:true}") boolean coverageEnabled
    ) {
        this(baseUrl, serviceKey, new JdkTransport(), dailyQuotaService::reserve,
                configuredExecutionEnabled, cursorRepository, retryRepository,
                relevanceClassifier, clock,
                new CoverageConfig(
                        coverageEnabled, fastPathMaxCalls, coverageListMaxCalls,
                        coverageDetailMaxCalls, coveragePageSize
                ), evidenceService::saveAll);
    }

    D2bBidCollector(String baseUrl, String serviceKey, Transport transport) {
        this(baseUrl, serviceKey, transport, () -> { }, false);
    }

    D2bBidCollector(String baseUrl, String serviceKey, Transport transport, CallQuota callQuota) {
        this(baseUrl, serviceKey, transport, callQuota, false);
    }

    D2bBidCollector(
            String baseUrl,
            String serviceKey,
            Transport transport,
            CallQuota callQuota,
            boolean configuredExecutionEnabled
    ) {
        this(baseUrl, serviceKey, transport, callQuota, configuredExecutionEnabled,
                null, null, null, Clock.systemUTC(), CoverageConfig.disabled());
    }

    D2bBidCollector(
            String baseUrl,
            String serviceKey,
            Transport transport,
            CallQuota callQuota,
            boolean configuredExecutionEnabled,
            BidSourceCollectionCursorRepository cursorRepository,
            BidSourceCollectionRetryRepository retryRepository,
            BidRelevanceClassifier relevanceClassifier,
            Clock clock,
            CoverageConfig coverageConfig
    ) {
        this(baseUrl, serviceKey, transport, callQuota, configuredExecutionEnabled,
                cursorRepository, retryRepository, relevanceClassifier, clock, coverageConfig, ignored -> { });
    }

    D2bBidCollector(String baseUrl, String serviceKey, Transport transport, CallQuota callQuota,
            boolean configuredExecutionEnabled, BidSourceCollectionCursorRepository cursorRepository,
            BidSourceCollectionRetryRepository retryRepository, BidRelevanceClassifier relevanceClassifier,
            Clock clock, CoverageConfig coverageConfig, Consumer<List<D2bCandidateObservation>> evidenceSink) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("D2B API 기본 URL이 비어 있습니다.");
        }
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.transport = transport;
        this.callQuota = callQuota;
        this.configuredExecutionEnabled = configuredExecutionEnabled;
        this.cursorRepository = cursorRepository;
        this.retryRepository = retryRepository;
        this.relevanceClassifier = relevanceClassifier;
        this.clock = clock;
        this.coverageConfig = coverageConfig;
        this.evidenceSink = evidenceSink;
        validateCoverageConfiguration();
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
        return collectMeasured(startDate, endDate).candidates();
    }

    public CollectionResult collectMeasured(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        if (serviceKey.isBlank()) {
            throw new IllegalStateException("D2B API 인증키가 설정되지 않았습니다.");
        }

        RequestBudget budget = new RequestBudget(coverageConfig.fastPathMaxCalls());
        RequestCounter counter = new RequestCounter();
        LinkedHashMap<CandidateKey, ListedNotice> listed = new LinkedHashMap<>();
        Map<CandidateKey, D2bCandidateObservation> observations = new LinkedHashMap<>();
        int successfulOperations = 0;
        List<FailureDiagnostic> failureDiagnostics = new ArrayList<>();

        for (Operation operation : Operation.values()) {
            try {
                for (ListedNotice notice : collectOperation(operation, startDate, endDate, budget, counter, observations)) {
                    listed.putIfAbsent(notice.key(), notice);
                }
                successfulOperations++;
            } catch (RuntimeException exception) {
                FailureDiagnostic diagnostic = diagnose(exception, FailureStage.LIST);
                failureDiagnostics.add(diagnostic);
                logFailure(operation, diagnostic);
            }
        }

        if (successfulOperations == 0) {
            queueFastPending(observations, startDate, endDate);
            persistEvidence(observations, null, counter);
            FailureDiagnostic diagnostic = failureDiagnostics.isEmpty()
                    ? FailureDiagnostic.fallback(FailureStage.LIST, IllegalStateException.class)
                    : failureDiagnostics.getFirst();
            throw new CollectionException("D2B list operations failed", counter.count(), diagnostic.errorCode());
        }

        LinkedHashMap<CandidateKey, BidQualificationDto> result = new LinkedHashMap<>();
        Set<CandidateKey> fastDetailConfirmed = new HashSet<>();
        for (ListedNotice notice : listed.values()) {
            Element detail = null;
            String detailStatus = "PENDING";
            if (budget.tryAcquire()) {
                try {
                    detail = readDetail(notice, counter);
                    detailStatus = "AVAILABLE";
                    fastDetailConfirmed.add(notice.key());
                } catch (RuntimeException exception) {
                    detailStatus = detailFailureStatus(exception);
                    logFailure(notice.operation, diagnose(exception, FailureStage.DETAIL));
                }
            }
            observe(observations, notice, "FAST", detail, detailStatus);
            result.put(notice.key(), toQualification(notice, detail));
        }
        String coverageToken = collectCoverage(
                startDate, endDate, counter, fastDetailConfirmed, result, observations
        );
        // Fast-path-only discoveries also need a durable route to detail verification.
        queueFastPending(observations, startDate, endDate);
        persistEvidence(observations, coverageToken, counter);
        return new CollectionResult(new ArrayList<>(result.values()), counter.count(), coverageToken);
    }

    private String collectCoverage(
            LocalDate startDate,
            LocalDate endDate,
            RequestCounter counter,
            Set<CandidateKey> fastDetailConfirmed,
            Map<CandidateKey, BidQualificationDto> result,
            Map<CandidateKey, D2bCandidateObservation> observations
    ) {
        if (!coverageConfig.enabled()) {
            return null;
        }
        Instant now = clock.instant();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            for (Operation operation : Operation.values()) {
                BidSourceCollectionCursor cursor = cursorRepository.getOrCreate(
                        SOURCE_CODE, COVERAGE_SCOPE, operation.name(), date, date,
                        coverageConfig.pageSize(), now
                );
                if (date.equals(endDate)
                        && cursor.status() == BidSourceCollectionCursor.Status.COMPLETE) {
                    cursorRepository.reopenCompleted(cursor.id(), cursor.version(), now);
                }
            }
        }

        RequestBudget listBudget = new RequestBudget(coverageConfig.listMaxCalls());
        RequestBudget detailBudget = new RequestBudget(coverageConfig.detailMaxCalls());
        List<CursorAdvance> advances = new ArrayList<>();
        List<Long> resolvedRetryIds = new ArrayList<>();
        Set<CandidateKey> confirmedKeys = new HashSet<>(fastDetailConfirmed);
        Set<CandidateKey> durableRetryKeys = new HashSet<>();
        boolean quotaExhausted = processPendingRetries(
                detailBudget, counter, confirmedKeys, durableRetryKeys, resolvedRetryIds, result, observations
        );

        for (BidSourceCollectionCursor cursor : cursorRepository.findIncomplete(SOURCE_CODE, COVERAGE_SCOPE)) {
            if (quotaExhausted || listBudget.remaining() <= 0) {
                break;
            }
            Operation operation = Operation.valueOf(cursor.operation());
            try {
                listBudget.acquire();
                CoveragePage page = readCoveragePage(operation, cursor, counter);
                List<ListedNotice> orderedNotices = new ArrayList<>(page.notices());
                orderedNotices.sort(Comparator.comparingInt(this::relevancePriority));
                for (ListedNotice notice : orderedNotices) {
                    observe(observations, notice, "COVERAGE", null, "PENDING");
                    CandidateKey key = notice.key();
                    if (confirmedKeys.contains(key) || durableRetryKeys.contains(key)) {
                        continue;
                    }
                    if (quotaExhausted || !detailBudget.tryAcquire()) {
                        savePendingRetry(cursor, notice, durableRetryKeys);
                        continue;
                    }
                    try {
                        Element detail = readDetail(notice, counter);
                        observe(observations, notice, "COVERAGE", detail, "AVAILABLE");
                        result.put(key, toQualification(notice, detail));
                        confirmedKeys.add(key);
                    } catch (RuntimeException exception) {
                        observe(observations, notice, "COVERAGE", null, detailFailureStatus(exception));
                        logFailure(operation, diagnose(exception, FailureStage.DETAIL));
                        BidSourceCollectionRetry retry = savePendingRetry(
                                cursor, notice, durableRetryKeys
                        );
                        retryRepository.recordFailure(
                                retry.id(), diagnose(exception, FailureStage.DETAIL).errorCode(),
                                clock.instant()
                        );
                        quotaExhausted = isDailyQuotaFailure(exception);
                    }
                }
                int nextPage = cursor.nextPage() + 1;
                boolean complete = page.totalCount() == 0
                        || (long) cursor.nextPage() * cursor.pageSize() >= page.totalCount();
                advances.add(new CursorAdvance(
                        cursor.id(), cursor.version(), nextPage, page.totalCount(),
                        complete ? BidSourceCollectionCursor.Status.COMPLETE
                                : BidSourceCollectionCursor.Status.PARTIAL
                ));
                if (quotaExhausted) {
                    break;
                }
            } catch (RuntimeException exception) {
                logFailure(operation, diagnose(exception, FailureStage.LIST));
                if (isDailyQuotaFailure(exception)) {
                    break;
                }
            }
        }
        if (advances.isEmpty() && resolvedRetryIds.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        pendingCoverageCompletions.put(token, new PendingCoverageCompletion(
                List.copyOf(advances), List.copyOf(resolvedRetryIds)
        ));
        return token;
    }

    private boolean processPendingRetries(
            RequestBudget detailBudget,
            RequestCounter counter,
            Set<CandidateKey> confirmedKeys,
            Set<CandidateKey> durableRetryKeys,
            List<Long> resolvedRetryIds,
            Map<CandidateKey, BidQualificationDto> result,
            Map<CandidateKey, D2bCandidateObservation> observations
    ) {
        for (BidSourceCollectionRetry retry : retryRepository.findPending(SOURCE_CODE, COVERAGE_SCOPE)) {
            ListedNotice notice = retryNotice(retry);
            observe(observations, notice, "RETRY", null, "PENDING");
            CandidateKey key = notice.key();
            durableRetryKeys.add(key);
            if (confirmedKeys.contains(key)) {
                resolvedRetryIds.add(retry.id());
                continue;
            }
            if (!detailBudget.tryAcquire()) {
                continue;
            }
            try {
                Element detail = readDetail(notice, counter);
                observe(observations, notice, "RETRY", detail, "AVAILABLE");
                result.put(key, toQualification(notice, detail));
                confirmedKeys.add(key);
                resolvedRetryIds.add(retry.id());
            } catch (RuntimeException exception) {
                observe(observations, notice, "RETRY", null, detailFailureStatus(exception));
                FailureDiagnostic diagnostic = diagnose(exception, FailureStage.DETAIL);
                logFailure(notice.operation(), diagnostic);
                retryRepository.recordFailure(retry.id(), diagnostic.errorCode(), clock.instant());
                if (isDailyQuotaFailure(exception)) {
                    return true;
                }
            }
        }
        return false;
    }

    private BidSourceCollectionRetry savePendingRetry(
            BidSourceCollectionCursor cursor,
            ListedNotice notice,
            Set<CandidateKey> durableRetryKeys
    ) {
        BidSourceCollectionRetry retry = retryRepository.savePending(
                SOURCE_CODE, COVERAGE_SCOPE, notice.operation().name(),
                cursor.windowStart(), cursor.windowEnd(), cursor.nextPage(),
                notice.sourceNoticeId(), notice.key().revision(), serializeValues(safePublicFields(notice.values())),
                clock.instant()
        );
        durableRetryKeys.add(notice.key());
        return retry;
    }

    private ListedNotice retryNotice(BidSourceCollectionRetry retry) {
        return new ListedNotice(Operation.valueOf(retry.operation()), deserializeValues(retry.payload()));
    }

    private void queueFastPending(Map<CandidateKey, D2bCandidateObservation> observations,
            LocalDate startDate, LocalDate endDate) {
        if (retryRepository == null) return;
        observations.values().stream()
                .filter(o -> o.paths().equals(Set.of("FAST")) && !"AVAILABLE".equals(o.detailStatus()))
                .forEach(o -> retryRepository.savePending(SOURCE_CODE, COVERAGE_SCOPE,
                        o.operations().stream().sorted().findFirst().orElseThrow(), startDate, endDate, 1,
                        o.sourceNoticeId(), o.revision(), serializeValues(o.listFields()), clock.instant()));
    }

    private void persistEvidence(Map<CandidateKey, D2bCandidateObservation> observations,
            String coverageToken, RequestCounter counter) {
        try {
            evidenceSink.accept(List.copyOf(observations.values()));
        } catch (RuntimeException exception) {
            completeCoverage(coverageToken, false);
            throw new CollectionException("D2B candidate evidence persistence failed",
                    counter.count(), "D2B_CANDIDATE_PERSISTENCE");
        }
    }

    private void observe(Map<CandidateKey, D2bCandidateObservation> observations, ListedNotice notice,
            String path, Element detail, String status) {
        D2bCandidateObservation previous = observations.get(notice.key());
        Set<String> operations = new HashSet<>(Set.of(notice.operation().name()));
        Set<String> paths = new HashSet<>(Set.of(path));
        Map<String, String> listedFields = safePublicFields(notice.values());
        Map<String, String> detailFields = detail == null ? Map.of() : safePublicFields(fields(detail));
        if (previous != null) {
            operations.addAll(previous.operations());
            paths.addAll(previous.paths());
            if ("AVAILABLE".equals(previous.detailStatus()) || "PENDING".equals(status)) {
                status = previous.detailStatus();
                detailFields = previous.detailFields();
            }
        }
        Map<String, String> titleFields = new LinkedHashMap<>(listedFields);
        detailFields.forEach((key, value) -> { if (!value.isBlank()) titleFields.put(key, value); });
        observations.put(notice.key(), new D2bCandidateObservation(
                safeEvidenceText(notice.sourceNoticeId()), safeEvidenceText(notice.key().revision()),
                operations, paths,
                firstValue(titleFields, notice.operation().titleField, "bidNm", "othbcNtatNm", "cntrwkNm"),
                listedFields, detailFields, status));
    }

    private Map<String, String> safePublicFields(Map<String, String> fields) {
        Map<String, String> selected = D2bCandidateEvidenceService.publicFields(fields);
        selected.replaceAll((key, value) -> safeEvidenceText(value));
        return selected;
    }

    private String safeEvidenceText(String value) {
        if (value == null) return "";
        if (!serviceKey.isBlank()) {
            value = value.replace(serviceKey, "[REDACTED]")
                    .replace(encodedServiceKey(serviceKey), "[REDACTED]");
        }
        return D2bCandidateEvidenceService.safeText(value);
    }

    private static String detailFailureStatus(RuntimeException exception) {
        return exception instanceof MissingDetailItemException ? "ITEM_MISSING" : "FAILED";
    }

    private static class MissingDetailItemException extends IllegalStateException {
        private MissingDetailItemException() { super("D2B detail response item is missing."); }
    }

    private int relevancePriority(ListedNotice notice) {
        String title = firstValue(
                notice.values(), notice.operation().titleField,
                "bidNm", "othbcNtatNm", "cntrwkNm"
        );
        return switch (relevanceClassifier.classify(title)) {
            case RELEVANT -> 0;
            case UNKNOWN -> 1;
            case IRRELEVANT -> 2;
        };
    }

    private String serializeValues(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException exception) {
            throw new IllegalStateException("D2B retry payload could not be serialized.", exception);
        }
    }

    private Map<String, String> deserializeValues(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (IOException exception) {
            throw new IllegalStateException("D2B retry payload could not be deserialized.", exception);
        }
    }

    private CoveragePage readCoveragePage(
            Operation operation,
            BidSourceCollectionCursor cursor,
            RequestCounter counter
    ) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("pageNo", Integer.toString(cursor.nextPage()));
        parameters.put("numOfRows", Integer.toString(cursor.pageSize()));
        parameters.put(operation.startDateParameter, cursor.windowStart().format(REQUEST_DATE));
        parameters.put(operation.endDateParameter, cursor.windowEnd().format(REQUEST_DATE));
        Document document = request(operation.listPath, parameters, counter);
        if (first(document, "body") == null || first(document, "totalCount") == null) {
            throw new ParseException(new IllegalStateException("D2B coverage page metadata is missing."));
        }
        int totalCount = requiredNonNegativeInteger(text(first(document, "totalCount")));
        List<Element> items = elements(document, "item");
        if (items.size() > cursor.pageSize()) {
            throw new ParseException(new IllegalStateException("D2B coverage page exceeds page size."));
        }
        List<ListedNotice> notices = new ArrayList<>();
        for (Element item : items) {
            ListedNotice notice = new ListedNotice(operation, fields(item));
            validateIdentity(notice);
            notices.add(notice);
        }
        return new CoveragePage(totalCount, List.copyOf(notices));
    }

    public void completeCoverage(String token, boolean persistenceSucceeded) {
        if (token == null || token.isBlank()) {
            return;
        }
        PendingCoverageCompletion completion = pendingCoverageCompletions.remove(token);
        if (!persistenceSucceeded || completion == null) {
            return;
        }
        for (Long retryId : completion.resolvedRetryIds()) {
            retryRepository.delete(retryId);
        }
        for (CursorAdvance advance : completion.cursorAdvances()) {
            boolean updated = cursorRepository.advance(
                    advance.id(), advance.expectedVersion(), advance.nextPage(),
                    advance.totalCount(), advance.status(), clock.instant()
            );
            if (!updated) {
                log.warn("D2B coverage cursor advance skipped due to concurrent update: cursorId={}",
                        advance.id());
            }
        }
    }

    private void validateCoverageConfiguration() {
        if (coverageConfig.fastPathMaxCalls() < Operation.values().length * SEARCH_TERMS.size()) {
            throw new IllegalArgumentException("D2B fast-path request budget is too small.");
        }
        if (!coverageConfig.enabled()) {
            return;
        }
        if (cursorRepository == null || retryRepository == null
                || relevanceClassifier == null || clock == null
                || coverageConfig.listMaxCalls() <= 0 || coverageConfig.detailMaxCalls() <= 0
                || coverageConfig.pageSize() <= 0
                || coverageConfig.fastPathMaxCalls() + coverageConfig.listMaxCalls()
                + coverageConfig.detailMaxCalls() > MAX_REQUESTS_PER_COLLECTION) {
            throw new IllegalArgumentException("Invalid D2B coverage configuration.");
        }
    }

    private static boolean isDailyQuotaFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof D2bDailyQuotaExceededException) {
                return true;
            }
        }
        return false;
    }

    private static int requiredNonNegativeInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ParseException(exception);
        }
    }

    private List<ListedNotice> collectOperation(
            Operation operation,
            LocalDate startDate,
            LocalDate endDate,
            RequestBudget budget,
            RequestCounter counter,
            Map<CandidateKey, D2bCandidateObservation> observations
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
                    ListedNotice notice = new ListedNotice(operation, fields(item));
                    validateIdentity(notice);
                    if (!isWithinRequestedPeriod(notice, startDate, endDate)) {
                        continue;
                    }
                    observe(observations, notice, "FAST", null, "PENDING");
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
        Element item = first(request(notice.operation.detailPath, parameters, counter), "item");
        if (item == null) throw new MissingDetailItemException();
        return item;
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
            throw new ApiResultException(safeResultCode(resultCode));
        }
        return document;
    }

    private void logFailure(Operation operation, FailureDiagnostic diagnostic) {
        log.warn(
                "D2B operation failed: operation={}, stage={}, exceptionClass={}, httpStatus={}, resultCode={}",
                operation.name(), diagnostic.stage(), diagnostic.exceptionClass(),
                diagnostic.httpStatus() == null ? "-" : diagnostic.httpStatus(),
                diagnostic.resultCode() == null ? "-" : diagnostic.resultCode()
        );
    }

    private static FailureDiagnostic diagnose(RuntimeException exception, FailureStage fallbackStage) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof D2bDailyQuotaExceededException) {
                return new FailureDiagnostic(
                        FailureStage.QUOTA, current.getClass().getSimpleName(),
                        null, null, "DAILY_QUOTA_EXCEEDED"
                );
            }
            if (current instanceof HttpStatusException httpFailure) {
                return new FailureDiagnostic(
                        FailureStage.HTTP, current.getClass().getSimpleName(),
                        httpFailure.statusCode(), null, "D2B_HTTP"
                );
            }
            if (current instanceof ApiResultException apiFailure) {
                return new FailureDiagnostic(
                        FailureStage.HTTP, current.getClass().getSimpleName(),
                        null, apiFailure.resultCode(), "D2B_RESULT_CODE"
                );
            }
            if (current instanceof ParseException) {
                return new FailureDiagnostic(
                        FailureStage.PARSE, current.getClass().getSimpleName(), null, null, "D2B_PARSE"
                );
            }
            if (current instanceof ConnectionException) {
                return new FailureDiagnostic(
                        FailureStage.CONNECTION, current.getClass().getSimpleName(), null, null, "D2B_CONNECTION"
                );
            }
        }
        return FailureDiagnostic.fallback(fallbackStage, exception.getClass());
    }

    private static String safeResultCode(String resultCode) {
        String normalized = resultCode == null ? "" : resultCode.trim();
        return SAFE_RESULT_CODE.matcher(normalized).matches() ? normalized : null;
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
        String licenseLimit = values.getOrDefault("lcnsLmttList", "");
        dto.setLicenseLimit(licenseLimit);
        dto.setParticipationRegion(values.getOrDefault("areaLmttList", ""));
        dto.setAttachments(List.of());
        dto.setLicenseGroups(parseLicenseGroups(licenseLimit));
        return dto;
    }

    static List<LicenseRequirementGroup> parseLicenseGroups(String licenseLimit) {
        if (licenseLimit == null || licenseLimit.isBlank()) {
            return List.of();
        }
        List<LicenseNormalizer.NormalizedLicense> licenses =
                LicenseNormalizer.normalizeStructuredQualification(licenseLimit);
        if (licenses.isEmpty()) {
            String raw = licenseLimit.trim();
            return List.of(new LicenseRequirementGroup("1", List.of(
                    new LicenseRequirement("1", "", raw, raw)
            )));
        }
        List<LicenseRequirement> requirements = new ArrayList<>();
        for (LicenseNormalizer.NormalizedLicense license : licenses) {
            requirements.add(new LicenseRequirement(
                    Integer.toString(requirements.size() + 1), license.code(), license.officialName(),
                    licenseLimit.trim()
            ));
        }
        return List.of(new LicenseRequirementGroup("1", List.copyOf(requirements)));
    }

    private static boolean isWithinRequestedPeriod(
            ListedNotice notice,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String value = firstValue(notice.values(), "pblancDate", "ntatPlanDate");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 8) {
            return true;
        }
        try {
            LocalDate publishedDate = LocalDate.parse(digits.substring(0, 8), REQUEST_DATE);
            return !publishedDate.isBefore(startDate) && !publishedDate.isAfter(endDate);
        } catch (RuntimeException ignored) {
            return true;
        }
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
            throw new ParseException(exception);
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

    enum FailureStage {
        LIST,
        DETAIL,
        PARSE,
        QUOTA,
        HTTP,
        CONNECTION
    }

    private record FailureDiagnostic(
            FailureStage stage,
            String exceptionClass,
            Integer httpStatus,
            String resultCode,
            String errorCode
    ) {
        private static FailureDiagnostic fallback(FailureStage stage, Class<?> exceptionClass) {
            return new FailureDiagnostic(
                    stage, exceptionClass.getSimpleName(), null, null, "D2B_" + stage.name()
            );
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

    public record CollectionResult(
            List<BidQualificationDto> candidates,
            int apiCallCount,
            String coverageToken
    ) {
        public CollectionResult(List<BidQualificationDto> candidates, int apiCallCount) {
            this(candidates, apiCallCount, null);
        }

        public CollectionResult {
            candidates = List.copyOf(candidates);
            if (apiCallCount < 0) {
                throw new IllegalArgumentException("apiCallCount must not be negative.");
            }
        }
    }

    record CoverageConfig(
            boolean enabled,
            int fastPathMaxCalls,
            int listMaxCalls,
            int detailMaxCalls,
            int pageSize
    ) {
        private static CoverageConfig disabled() {
            return new CoverageConfig(false, MAX_REQUESTS_PER_COLLECTION, 0, 0, 0);
        }
    }

    private record CoveragePage(int totalCount, List<ListedNotice> notices) {
    }

    private record CursorAdvance(
            long id,
            long expectedVersion,
            int nextPage,
            int totalCount,
            BidSourceCollectionCursor.Status status
    ) {
    }

    private record PendingCoverageCompletion(
            List<CursorAdvance> cursorAdvances,
            List<Long> resolvedRetryIds
    ) {
    }

    public static class CollectionException extends IllegalStateException {
        private final int apiCallCount;
        private final String errorCode;

        CollectionException(String message, int apiCallCount, String errorCode) {
            super(message);
            this.apiCallCount = apiCallCount;
            this.errorCode = errorCode;
        }

        public int getApiCallCount() {
            return apiCallCount;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    static final class HttpStatusException extends IllegalStateException {
        private final int statusCode;

        HttpStatusException(int statusCode) {
            super("D2B HTTP request failed");
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    private static final class ApiResultException extends IllegalStateException {
        private final String resultCode;

        private ApiResultException(String resultCode) {
            super("D2B API returned an error result");
            this.resultCode = resultCode;
        }

        private String resultCode() {
            return resultCode;
        }
    }

    private static final class ParseException extends IllegalStateException {
        private ParseException(Throwable cause) {
            super("D2B response parsing failed", cause);
        }
    }

    static final class ConnectionException extends IllegalStateException {
        ConnectionException(Throwable cause) {
            super("D2B connection failed", cause);
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

        private int remaining() {
            return remaining;
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
                        throw new HttpStatusException(response.statusCode());
                    }
                    byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("D2B API 응답 크기 제한을 초과했습니다.");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                throw new ConnectionException(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ConnectionException(exception);
            }
        }
    }
}
