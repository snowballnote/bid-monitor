package com.comhu.bidmonitor.bid.source.registration;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record DiscoveredPublicPageSourceConfig(
        long sourceId,
        Optional<String> sourceCode,
        URI listPageUrl,
        String detailUrlPattern,
        MappingValue identifierMapping,
        MappingValue titleMapping,
        MappingValue agencyMapping,
        MappingValue publishedDateMapping,
        MappingValue deadlineMapping,
        MappingValue statusMapping,
        MappingValue attachmentMapping,
        MappingValue paginationMapping,
        Instant reviewUpdatedAt
) {
    public DiscoveredPublicPageSourceConfig {
        if (sourceId < 1) throw new IllegalArgumentException("sourceId must be positive.");
        sourceCode = Objects.requireNonNull(sourceCode, "sourceCode is required.")
                .map(String::trim).filter(value -> !value.isEmpty());
        Objects.requireNonNull(listPageUrl, "listPageUrl is required.");
        if (detailUrlPattern == null || detailUrlPattern.isBlank()) {
            throw new IllegalArgumentException("detailUrlPattern is required.");
        }
        requireConfigured(identifierMapping, "identifierMapping");
        requireConfigured(titleMapping, "titleMapping");
        Objects.requireNonNull(agencyMapping, "agencyMapping is required.");
        Objects.requireNonNull(publishedDateMapping, "publishedDateMapping is required.");
        Objects.requireNonNull(deadlineMapping, "deadlineMapping is required.");
        Objects.requireNonNull(statusMapping, "statusMapping is required.");
        Objects.requireNonNull(attachmentMapping, "attachmentMapping is required.");
        Objects.requireNonNull(paginationMapping, "paginationMapping is required.");
        Objects.requireNonNull(reviewUpdatedAt, "reviewUpdatedAt is required.");
    }

    private static void requireConfigured(MappingValue mapping, String field) {
        if (mapping == null || mapping.state() != MappingState.CONFIGURED) {
            throw new IllegalArgumentException(field + " must be configured.");
        }
    }

    public record MappingValue(MappingState state, Optional<String> expression) {
        public MappingValue {
            Objects.requireNonNull(state, "Mapping state is required.");
            expression = Objects.requireNonNull(expression, "Mapping expression is required.")
                    .map(String::trim).filter(value -> !value.isEmpty());
            if ((state == MappingState.UNKNOWN) != expression.isEmpty()) {
                throw new IllegalArgumentException("UNKNOWN mappings cannot have an expression.");
            }
        }

        public static MappingValue configured(String expression) {
            return new MappingValue(MappingState.CONFIGURED, Optional.ofNullable(expression));
        }

        public static MappingValue unknown() {
            return new MappingValue(MappingState.UNKNOWN, Optional.empty());
        }
    }

    public enum MappingState {
        CONFIGURED,
        UNKNOWN
    }
}
