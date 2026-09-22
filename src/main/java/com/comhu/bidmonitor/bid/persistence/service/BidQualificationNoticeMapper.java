package com.comhu.bidmonitor.bid.persistence.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BidQualificationNoticeMapper {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
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
                .publishedAt(parseDateTime(source.getBidNtceDt(), "bidNtceDt"))
                .submissionDeadlineAt(parseDateTime(source.getBidClseDt(), "bidClseDt"))
                .bidOpeningAt(parseDateTime(source.getBidOpeningDt(), "bidOpeningDt"))
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
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bid analysis result could not be serialized.", exception);
        }
    }

    private List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::nullable).sorted().toList();
    }

    private LocalDateTime parseDateTime(String value, String field) {
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
        throw new IllegalArgumentException(field + " has an unsupported date format.");
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
