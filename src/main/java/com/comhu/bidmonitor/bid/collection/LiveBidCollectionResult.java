package com.comhu.bidmonitor.bid.collection;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** In-memory collection result returned directly to the live notice screen. */
public record LiveBidCollectionResult(
        Status status,
        LocalDate startDate,
        LocalDate endDate,
        Set<String> allowedIndustryCodes,
        List<SourceResult> sources
) {

    public LiveBidCollectionResult {
        if (status == null || startDate == null || endDate == null) {
            throw new IllegalArgumentException("Live collection result fields are required.");
        }
        allowedIndustryCodes = allowedIndustryCodes == null ? Set.of() : Set.copyOf(allowedIndustryCodes);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public List<BidQualificationDto> notices() {
        return sources.stream()
                .filter(source -> source.status() == SourceStatus.SUCCESS)
                .flatMap(source -> source.notices().stream())
                .toList();
    }

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED,
        NO_ACTIVE_SOURCES
    }

    public enum SourceStatus {
        SUCCESS,
        FAILED
    }

    public record SourceResult(
            String sourceCode,
            SourceStatus status,
            List<BidQualificationDto> notices,
            String errorCode
    ) {

        public SourceResult {
            if (sourceCode == null || sourceCode.isBlank() || status == null) {
                throw new IllegalArgumentException("Live source result identity is required.");
            }
            sourceCode = sourceCode.trim();
            notices = notices == null ? List.of() : List.copyOf(notices);
            if (status == SourceStatus.SUCCESS && errorCode != null) {
                throw new IllegalArgumentException("A successful live source result cannot have an error code.");
            }
            if (status == SourceStatus.FAILED && !notices.isEmpty()) {
                throw new IllegalArgumentException("A failed live source result cannot contain notices.");
            }
        }

        public static SourceResult success(String sourceCode, List<BidQualificationDto> notices) {
            return new SourceResult(sourceCode, SourceStatus.SUCCESS, notices, null);
        }

        public static SourceResult failure(String sourceCode, String errorCode) {
            return new SourceResult(sourceCode, SourceStatus.FAILED, List.of(), errorCode);
        }
    }
}
