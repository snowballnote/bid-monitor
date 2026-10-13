package com.comhu.bidmonitor.bid.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionRequest;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionResponse;
import com.comhu.bidmonitor.bid.api.dto.BidCollectionRunResponse;
import com.comhu.bidmonitor.bid.api.dto.BidSourceStatusResponse;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionCoordinator;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionResult;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSource;
import com.comhu.bidmonitor.bid.collection.ManualBidCollectionSourceRegistry;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import com.comhu.bidmonitor.bid.source.registration.BidSourceCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class BidCollectionController {

    private static final Set<String> DEFAULT_ALLOWED_LICENSE_CODES = Set.of("6146", "1468");
    private static final Pattern SOURCE_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final int MAX_PAGE_SIZE = 100;

    private final ManualBidCollectionCoordinator coordinator;
    private final BidCollectionRunRepository runRepository;
    private final BidNoticeRepository noticeRepository;
    private final BidSourceStateRepository stateRepository;
    private final ManualBidCollectionSourceRegistry sourceRegistry;
    private final BidSourceCatalogService sourceCatalogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BidCollectionController(
            ManualBidCollectionCoordinator coordinator,
            BidCollectionRunRepository runRepository,
            BidNoticeRepository noticeRepository,
            BidSourceStateRepository stateRepository,
            ManualBidCollectionSourceRegistry sourceRegistry,
            BidSourceCatalogService sourceCatalogService
    ) {
        this.coordinator = coordinator;
        this.runRepository = runRepository;
        this.noticeRepository = noticeRepository;
        this.stateRepository = stateRepository;
        this.sourceRegistry = sourceRegistry;
        this.sourceCatalogService = sourceCatalogService;
    }

    @PostMapping("/bid-collections")
    public BidCollectionResponse collect(@RequestBody BidCollectionRequest request) {
        validate(request);
        ManualBidCollectionResult result = coordinator.collect(
                request.startDate(), request.endDate(), allowedLicenseCodes(request),
                request.sourceCodes() == null ? Set.of() : request.sourceCodes()
        );
        return BidCollectionResponse.from(result);
    }

    @GetMapping("/bid-collections/{runId}")
    public BidCollectionRunResponse getRun(@PathVariable long runId) {
        BidCollectionRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BidCollectionRunNotFoundException(runId));
        return BidCollectionRunResponse.from(run);
    }

    @GetMapping("/bid-notices")
    public BidNoticeListResponse getBidNotices(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String sourceCode,
            @RequestParam(name = "runId", required = false) List<Long> runIds,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size
    ) {
        LocalDate parsedStartDate = parseDate(startDate, "startDate");
        LocalDate parsedEndDate = parseDate(endDate, "endDate");
        if (parsedStartDate != null && parsedEndDate != null
                && parsedStartDate.isAfter(parsedEndDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate.");
        }
        String normalizedSourceCode = normalizeSourceCode(sourceCode);
        int parsedPage = parseNonNegativeInteger(page, "page");
        int parsedSize = parsePositiveInteger(size, "size");
        if (parsedSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must not exceed " + MAX_PAGE_SIZE + ".");
        }

        List<Long> normalizedRunIds = normalizeRunIds(runIds);
        BidNoticeRepository.BidNoticePage result = normalizedRunIds.isEmpty()
                ? noticeRepository.findLatest(
                        parsedStartDate, parsedEndDate, normalizedSourceCode, parsedPage, parsedSize
                )
                : noticeRepository.findLatestByRunIds(
                        normalizedRunIds, parsedStartDate, parsedEndDate,
                        normalizedSourceCode, parsedPage, parsedSize
                );
        return BidNoticeListResponse.from(result, parsedPage, parsedSize, objectMapper);
    }

    @GetMapping("/bid-sources/status")
    public List<BidSourceStatusResponse> getSourceStatuses() {
        Map<String, Boolean> enabledBySource = new LinkedHashMap<>();
        for (ManualBidCollectionSource source : sourceRegistry.sources()) {
            enabledBySource.put(source.sourceCode(), source.executionEnabled());
        }

        Map<String, BidSourceState> storedStates = new LinkedHashMap<>();
        stateRepository.findAll().forEach(state -> storedStates.put(state.getSourceCode(), state));

        List<BidSourceStatusResponse> result = new ArrayList<>();
        enabledBySource.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BidSourceState state = storedStates.getOrDefault(
                            entry.getKey(), BidSourceState.builder().sourceCode(entry.getKey()).build()
                    );
                    result.add(BidSourceStatusResponse.from(state, entry.getValue()));
                });
        return List.copyOf(result);
    }

    private void validate(BidCollectionRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required.");
        }
        if (request.startDate().isAfter(request.endDate())) {
            throw new IllegalArgumentException("startDate must not be after endDate.");
        }
    }

    private Set<String> allowedLicenseCodes(BidCollectionRequest request) {
        if (request.allowedLicenseCodes() == null || request.allowedLicenseCodes().isEmpty()) {
            return DEFAULT_ALLOWED_LICENSE_CODES;
        }
        return Set.copyOf(request.allowedLicenseCodes());
    }

    private LocalDate parseDate(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a valid ISO date.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(field + " must be a valid ISO date.");
        }
    }

    private String normalizeSourceCode(String sourceCode) {
        if (sourceCode == null) {
            return null;
        }
        String normalized = sourceCode.trim().toUpperCase(Locale.ROOT);
        boolean catalogSource = SOURCE_CODE_PATTERN.matcher(normalized).matches()
                && sourceCatalogService.catalog().stream()
                .anyMatch(source -> normalized.equals(source.sourceCode()));
        if (!catalogSource) {
            throw new IllegalArgumentException("Unsupported bid source.");
        }
        return normalized;
    }

    private List<Long> normalizeRunIds(List<Long> runIds) {
        if (runIds == null) {
            return List.of();
        }
        if (runIds.stream().anyMatch(runId -> runId == null || runId <= 0)) {
            throw new IllegalArgumentException("runId must be positive.");
        }
        return runIds.stream().distinct().toList();
    }

    private int parseNonNegativeInteger(String value, String field) {
        int parsed = parseInteger(value, field);
        if (parsed < 0) {
            throw new IllegalArgumentException(field + " must not be negative.");
        }
        return parsed;
    }

    private int parsePositiveInteger(String value, String field) {
        int parsed = parseInteger(value, field);
        if (parsed < 1) {
            throw new IllegalArgumentException(field + " must be positive.");
        }
        return parsed;
    }

    private int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a valid integer.");
        }
    }

    public record BidNoticeListResponse(
            List<BidNoticeResponse> items,
            int page,
            int size,
            long totalCount,
            long totalPages
    ) {

        private static BidNoticeListResponse from(
                BidNoticeRepository.BidNoticePage result,
                int page,
                int size,
                ObjectMapper objectMapper
        ) {
            List<BidNoticeResponse> items = result.items().stream()
                    .map(notice -> BidNoticeResponse.from(notice, objectMapper))
                    .toList();
            long totalPages = result.totalCount() / size
                    + (result.totalCount() % size == 0 ? 0 : 1);
            return new BidNoticeListResponse(items, page, size, result.totalCount(), totalPages);
        }
    }

    public record BidNoticeResponse(
            String sourceCode,
            String sourceNoticeId,
            String revision,
            String noticeNumber,
            String title,
            String orderingOrganization,
            LocalDateTime publishedAt,
            LocalDateTime submissionDeadlineAt,
            LocalDateTime bidOpeningAt,
            String contractMethod,
            String bidMethod,
            String noticeStatus,
            String detailUrl,
            String analysisStatus,
            String externalCheckStatus,
            Boolean externalSiteCheckRequired,
            List<String> externalSiteUrls,
            String externalCheckReason,
            String awardMethodCategory,
            String awardMethodStatus,
            String awardMethodReason,
            String awardMethodSource,
            String reviewStatus,
            String reviewReason,
            String budgetAmount,
            String licenseLimit,
            String participationRegion,
            String awardMethodName,
            List<Map<String, Object>> attachments,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {

        private static BidNoticeResponse from(BidNotice notice, ObjectMapper objectMapper) {
            JsonNode analysis = parseAnalysis(notice.getAnalysisResult(), objectMapper);
            return new BidNoticeResponse(
                    notice.getSourceCode(),
                    notice.getSourceNoticeId(),
                    notice.getRevisionKey(),
                    notice.getNoticeNumber(),
                    notice.getTitle(),
                    notice.getOrderingOrganization(),
                    notice.getPublishedAt(),
                    notice.getSubmissionDeadlineAt(),
                    notice.getBidOpeningAt(),
                    notice.getContractMethod(),
                    notice.getBidMethod(),
                    notice.getNoticeStatus(),
                    notice.getDetailUrl(),
                    notice.getAnalysisStatus(),
                    text(analysis, "externalCheckStatus"),
                    bool(analysis, "externalSiteCheckRequired"),
                    stringArray(analysis, "externalSiteUrls", objectMapper),
                    text(analysis, "externalCheckReason"),
                    text(analysis, "awardMethodCategory"),
                    text(analysis, "awardMethodStatus"),
                    text(analysis, "awardMethodReason"),
                    text(analysis, "awardMethodSource"),
                    firstNonBlank(text(analysis, "reviewStatus"), notice.getAnalysisStatus()),
                    text(analysis, "reviewReason"),
                    text(analysis, "budgetAmount"),
                    text(analysis, "licenseLimit"),
                    text(analysis, "participationRegion"),
                    text(analysis, "awardMethodName"),
                    objectArray(analysis, "attachments", objectMapper),
                    notice.getFirstSeenAt(),
                    notice.getLastSeenAt()
            );
        }

        private static JsonNode parseAnalysis(String value, ObjectMapper objectMapper) {
            if (value == null || value.isBlank()) {
                return objectMapper.createObjectNode();
            }
            try {
                JsonNode parsed = objectMapper.readTree(value);
                return parsed != null && parsed.isObject() ? parsed : objectMapper.createObjectNode();
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }

        private static String text(JsonNode analysis, String field) {
            JsonNode value = analysis.path(field);
            return value.isTextual() ? value.textValue() : null;
        }

        private static Boolean bool(JsonNode analysis, String field) {
            JsonNode value = analysis.path(field);
            return value.isBoolean() ? value.booleanValue() : null;
        }

        private static List<String> stringArray(JsonNode analysis, String field, ObjectMapper objectMapper) {
            JsonNode value = analysis.path(field);
            return value.isArray()
                    ? objectMapper.convertValue(value, new TypeReference<List<String>>() { })
                    : List.of();
        }

        private static List<Map<String, Object>> objectArray(
                JsonNode analysis,
                String field,
                ObjectMapper objectMapper
        ) {
            JsonNode value = analysis.path(field);
            return value.isArray()
                    ? objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() { })
                    : List.of();
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first : second;
        }
    }
}
