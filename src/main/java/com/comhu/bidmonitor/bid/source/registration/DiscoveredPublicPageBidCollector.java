package com.comhu.bidmonitor.bid.source.registration;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DiscoveredPublicPageBidCollector implements BidCandidateCollector {

    private final long sourceId;
    private final DiscoveredBidSourceConfigFactory configFactory;
    private final DiscoveredPublicPageCollectionRunner collectionRunner;

    public DiscoveredPublicPageBidCollector(
            long sourceId,
            DiscoveredBidSourceConfigFactory configFactory,
            DiscoveredPublicPageCollectionRunner collectionRunner
    ) {
        if (sourceId < 1) throw new IllegalArgumentException("sourceId must be positive.");
        this.sourceId = sourceId;
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory is required.");
        this.collectionRunner = Objects.requireNonNull(collectionRunner, "collectionRunner is required.");
    }

    public long sourceId() {
        return sourceId;
    }

    @Override
    public String sourceCode() {
        return executableConfig().flatMap(DiscoveredPublicPageSourceConfig::sourceCode).orElse(null);
    }

    @Override
    public boolean executionEnabled() {
        return executableConfig().flatMap(DiscoveredPublicPageSourceConfig::sourceCode).isPresent();
    }

    @Override
    public boolean registrationBindingSupported() {
        return true;
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        DiscoveredPublicPageSourceConfig config = executableConfig()
                .orElseThrow(() -> failure("CONFIG_NOT_EXECUTABLE"));
        if (config.sourceCode().isEmpty()) throw failure("SOURCE_CODE_REQUIRED");
        try {
            return collectionRunner.collect(config);
        } catch (DiscoveredPublicPageCollectionRunner.CollectionFailureException exception) {
            throw new CollectionException(exception.safeCode(), exception);
        }
    }

    private Optional<DiscoveredPublicPageSourceConfig> executableConfig() {
        return configFactory.create(sourceId);
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw failure("INVALID_COLLECTION_RANGE");
        }
    }

    private CollectionException failure(String safeCode) {
        return new CollectionException(safeCode, null);
    }

    public static final class CollectionException extends IllegalStateException {
        private final String safeCode;

        private CollectionException(String safeCode, Throwable cause) {
            super(safeCode, cause);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
