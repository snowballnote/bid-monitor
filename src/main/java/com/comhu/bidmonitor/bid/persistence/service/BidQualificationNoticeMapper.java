package com.comhu.bidmonitor.bid.persistence.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Slf4j
public class BidQualificationNoticeMapper {

    private static final Pattern INDUSTRY_CODE_PATTERN = Pattern.compile("[0-9]{4}");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    );
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BidNoticeContentHasher contentHasher;

    public BidQualificationNoticeMapper(BidNoticeContentHasher contentHasher) {
        this.contentHasher = contentHasher;
    }

    public BidNotice map(BidQualificationDto source, Instant collectedAt) {
        if (source == null) {
            throw new IllegalArgumentException("Bid candidate is required.");
        }
        if (collectedAt == null) {
            throw new IllegalArgumentException("collectedAt is required.");
        }
        String detailUrl = firstNonBlank(source.getDetailUrl(), source.getBidNtceDtlUrl());
        BidNotice withoutHash = BidNotice.builder()
                .sourceCode(required(source.getSourceCode(), "sourceCode"))
                .sourceNoticeId(required(source.getSourceNoticeId(), "sourceNoticeId"))
                .revisionKey(nullable(source.getRevision()))
                .noticeNumber(nullable(source.getBidNtceNo()))
                .title(required(source.getBidNtceNm(), "bidNtceNm"))
                .orderingOrganization(nullable(source.getNtceInsttNm()))
                .publishedAt(parseDateTime(source.getBidNtceDt(), "bidNtceDt", source.getSourceNoticeId()))
                .submissionDeadlineAt(parseDateTime(
                        source.getBidClseDt(), "bidClseDt", source.getSourceNoticeId()
                ))
                .bidOpeningAt(parseDateTime(
                        source.getBidOpeningDt(), "bidOpeningDt", source.getSourceNoticeId()
                ))
                .contractMethod(nullable(source.getContractMethod()))
                .bidMethod(nullable(source.getBidForm()))
                .noticeStatus(nullable(source.getNoticeStatus()))
                .noticeStatusCode(nullable(source.getNoticeStatusCode()))
                .detailUrl(detailUrl)
                .relevant(true)
                .analysisStatus(firstNonBlank(source.getReviewStatus(), "UNKNOWN"))
                .analysisResult(analysisResult(source))
                .contentHash("0".repeat(64))
                .firstSeenAt(collectedAt)
                .lastSeenAt(collectedAt)
                .build();
        return withoutHash.toBuilder()
                .contentHash(contentHasher.hash(withoutHash, source))
                .build();
    }

    private String analysisResult(BidQualificationDto source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalCheckStatus", nullable(source.getExternalCheckStatus()));
        result.put("externalSiteCheckRequired", source.getExternalSiteCheckRequired());
        result.put("externalSiteUrls", sorted(source.getExternalSiteUrls()));
        result.put("externalCheckReason", nullable(source.getExternalCheckReason()));
        result.put("awardMethodCategory", nullable(source.getAwardMethodCategory()));
        result.put("awardMethodStatus", nullable(source.getAwardMethodStatus()));
        result.put("awardMethodReason", nullable(source.getAwardMethodReason()));
        result.put("awardMethodSource", nullable(source.getAwardMethodSource()));
        result.put("reviewStatus", nullable(source.getReviewStatus()));
        result.put("reviewReason", nullable(source.getReviewReason()));
        result.put("budgetAmount", nullable(source.getAsignBdgtAmt()));
        result.put("licenseLimit", nullable(source.getLicenseLimit()));
        result.put("licenseCodes", licenseCodes(source.getLicenseGroups()));
        result.put("participationRegion", nullable(source.getParticipationRegion()));
        result.put("awardMethodName", nullable(source.getSucsfbidMthdNm()));
        result.put("attachments", source.getAttachments() == null ? List.of() : source.getAttachments());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bid analysis result could not be serialized.", exception);
        }
    }

    private List<String> licenseCodes(List<LicenseRequirementGroup> groups) {
        if (groups == null) {
            return List.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (LicenseRequirementGroup group : groups) {
            if (group == null || group.getRequirements() == null) {
                continue;
            }
            for (LicenseRequirement requirement : group.getRequirements()) {
                if (requirement == null) {
                    continue;
                }
                String code = nullable(requirement.getLicenseCode());
                if (code != null && INDUSTRY_CODE_PATTERN.matcher(code).matches()) {
                    codes.add(code);
                }
            }
        }
        return List.copyOf(codes);
    }

    private List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::nullable).sorted().toList();
    }

    private LocalDateTime parseDateTime(String value, String field, String sourceNoticeId) {
        String normalized = nullable(value);
        if (normalized == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(normalized).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Most current collectors return a local Korean date or date-time without an offset.
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported source format.
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(normalized, formatter).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // Try the next supported source format.
            }
        }
        IllegalArgumentException exception =
                new IllegalArgumentException(field + " has an unsupported date format.");
        if (log.isDebugEnabled()) {
            log.debug(
                    "Bid candidate date parsing failed: fieldName={}, sourceNoticeId={}, rawValue={}, "
                            + "errorType={}, errorMessage={}",
                    field,
                    sourceNoticeId,
                    safeDateValue(normalized),
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
        }
        throw exception;
    }

    private String safeDateValue(String value) {
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return singleLine.length() <= 100 ? singleLine : singleLine.substring(0, 100) + "...";
    }

    private String firstNonBlank(String first, String second) {
        String normalizedFirst = nullable(first);
        return normalizedFirst != null ? normalizedFirst : nullable(second);
    }

    private String required(String value, String field) {
        String normalized = nullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
