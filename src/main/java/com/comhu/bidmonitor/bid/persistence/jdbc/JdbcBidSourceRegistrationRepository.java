package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceCheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcBidSourceRegistrationRepository implements BidSourceRegistrationRepository {

    private static final String COLUMNS = """
            source_id, source_name, site_url, source_code, registration_status, collection_method,
            execution_enabled, check_status, detected_collection_method, http_status,
            content_type, checked_at, safe_failure_code, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceRegistrationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BidSourceRegistration save(BidSourceRegistration registration) {
        validateNew(registration);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bid_source_registration (
                        source_name, site_url, registration_status, collection_method,
                        execution_enabled, check_status, detected_collection_method,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, registration.getSourceName());
            statement.setString(2, registration.getSiteUrl());
            statement.setString(3, registration.getRegistrationStatus().name());
            statement.setString(4, registration.getCollectionMethod().name());
            statement.setBoolean(5, registration.isExecutionEnabled());
            statement.setString(6, registration.getCheckStatus().name());
            statement.setString(7, registration.getDetectedCollectionMethod().name());
            statement.setTimestamp(8, Timestamp.from(registration.getCreatedAt()));
            statement.setTimestamp(9, Timestamp.from(registration.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number sourceId = keyHolder.getKey();
        if (sourceId == null) {
            throw new IllegalStateException("Created bid source registration has no identifier.");
        }
        return findById(sourceId.longValue())
                .orElseThrow(() -> new IllegalStateException("Created bid source registration was not found."));
    }

    @Override
    public Optional<BidSourceRegistration> findById(long sourceId) {
        if (sourceId < 1) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_registration WHERE source_id = ?",
                this::map,
                sourceId
        ).stream().findFirst();
    }

    @Override
    public Optional<BidSourceRegistration> findBySourceCode(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_registration WHERE source_code = ?",
                this::map,
                sourceCode
        ).stream().findFirst();
    }

    @Override
    public boolean bindSourceCode(
            long sourceId,
            BidSourceRegistration.RegistrationStatus expectedStatus,
            String sourceCode,
            Instant updatedAt
    ) {
        Objects.requireNonNull(expectedStatus, "Expected registration status is required.");
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code is required.");
        }
        Objects.requireNonNull(updatedAt, "Binding update time is required.");
        return jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET source_code = ?, updated_at = ?
                        WHERE source_id = ? AND registration_status = ?
                          AND source_code IS NULL AND execution_enabled = FALSE
                        """,
                sourceCode,
                Timestamp.from(updatedAt),
                sourceId,
                expectedStatus.name()
        ) == 1;
    }

    @Override
    public boolean updateExecutionEnabled(long sourceId, boolean executionEnabled, Instant updatedAt) {
        Objects.requireNonNull(updatedAt, "Activation update time is required.");
        if (!executionEnabled) {
            return jdbcTemplate.update("""
                            UPDATE bid_source_registration
                            SET execution_enabled = FALSE, updated_at = ?
                            WHERE source_id = ?
                            """,
                    Timestamp.from(updatedAt), sourceId
            ) == 1;
        }
        return jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET execution_enabled = TRUE, updated_at = ?
                        WHERE source_id = ?
                          AND registration_status = 'APPROVED'
                          AND source_code IS NOT NULL
                          AND collection_method <> 'UNDETERMINED'
                          AND collection_method = detected_collection_method
                        """,
                Timestamp.from(updatedAt), sourceId
        ) == 1;
    }

    @Override
    public boolean updateReview(
            long sourceId,
            BidSourceRegistration.RegistrationStatus expectedStatus,
            BidSourceRegistration.RegistrationStatus registrationStatus,
            BidSourceRegistration.CollectionMethod collectionMethod,
            Instant updatedAt
    ) {
        Objects.requireNonNull(expectedStatus, "Expected registration status is required.");
        Objects.requireNonNull(registrationStatus, "Registration status is required.");
        Objects.requireNonNull(collectionMethod, "Collection method is required.");
        Objects.requireNonNull(updatedAt, "Review update time is required.");
        return jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET registration_status = ?, collection_method = ?, updated_at = ?
                        WHERE source_id = ? AND registration_status = ? AND execution_enabled = FALSE
                        """,
                registrationStatus.name(),
                collectionMethod.name(),
                Timestamp.from(updatedAt),
                sourceId,
                expectedStatus.name()
        ) == 1;
    }

    @Override
    @Transactional
    public CheckStartOutcome tryStartCheck(
            long sourceId,
            String attemptId,
            Instant startedAt,
            Instant expiredBefore
    ) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("Check attempt identifier is required.");
        }
        Objects.requireNonNull(startedAt, "Check start time is required.");
        Objects.requireNonNull(expiredBefore, "Check expiry cutoff is required.");
        int expired = jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET check_status = 'UNREACHABLE',
                            detected_collection_method = 'UNDETERMINED',
                            http_status = NULL, content_type = NULL,
                            check_attempt_id = NULL, check_started_at = NULL, checked_at = ?,
                            safe_failure_code = 'CHECK_TIMEOUT'
                        WHERE source_id = ?
                          AND registration_status IN ('PENDING_REVIEW', 'UNDER_REVIEW')
                          AND check_status = 'CHECKING'
                          AND (check_started_at IS NULL OR check_started_at <= ?)
                          AND execution_enabled = FALSE
                        """,
                Timestamp.from(startedAt),
                sourceId,
                Timestamp.from(expiredBefore)
        );
        int started = jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET check_status = 'CHECKING', detected_collection_method = 'UNDETERMINED',
                            http_status = NULL, content_type = NULL, check_started_at = ?,
                            check_attempt_id = ?, checked_at = NULL, safe_failure_code = ?
                        WHERE source_id = ?
                          AND registration_status IN ('PENDING_REVIEW', 'UNDER_REVIEW')
                          AND check_status <> 'CHECKING'
                          AND execution_enabled = FALSE
                        """,
                Timestamp.from(startedAt),
                attemptId,
                expired == 1 ? BidSourceRegistration.SafeFailureCode.CHECK_TIMEOUT.name() : null,
                sourceId
        );
        if (started == 0) {
            return CheckStartOutcome.REJECTED;
        }
        return expired == 1
                ? CheckStartOutcome.RESTARTED_AFTER_TIMEOUT
                : CheckStartOutcome.STARTED;
    }

    @Override
    public boolean updateCheckResult(
            long sourceId,
            String expectedAttemptId,
            BidSourceCheckResult result
    ) {
        if (expectedAttemptId == null || expectedAttemptId.isBlank()) {
            throw new IllegalArgumentException("Expected check attempt identifier is required.");
        }
        Objects.requireNonNull(result, "Bid source check result is required.");
        return jdbcTemplate.update("""
                        UPDATE bid_source_registration
                        SET check_status = ?, detected_collection_method = ?, http_status = ?,
                            content_type = ?, check_attempt_id = NULL, check_started_at = NULL,
                            checked_at = ?, safe_failure_code = ?
                        WHERE source_id = ? AND check_status = 'CHECKING'
                          AND check_attempt_id = ? AND execution_enabled = FALSE
                        """,
                result.checkStatus().name(),
                result.detectedCollectionMethod().name(),
                result.httpStatus(),
                result.contentType(),
                Timestamp.from(result.checkedAt()),
                result.safeFailureCode() == null ? null : result.safeFailureCode().name(),
                sourceId,
                expectedAttemptId
        ) == 1;
    }

    @Override
    public List<BidSourceRegistration> findAllLatestFirst() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM bid_source_registration ORDER BY created_at DESC, source_id DESC",
                this::map
        );
    }

    private void validateNew(BidSourceRegistration registration) {
        Objects.requireNonNull(registration, "Bid source registration is required.");
        if (registration.getSourceId() != null
                || registration.getSourceCode() != null
                || registration.getSourceName() == null || registration.getSourceName().isBlank()
                || registration.getSiteUrl() == null || registration.getSiteUrl().isBlank()
                || registration.getRegistrationStatus() == null
                || registration.getCollectionMethod() == null
                || registration.getCheckStatus() == null
                || registration.getDetectedCollectionMethod() == null
                || registration.getCreatedAt() == null || registration.getUpdatedAt() == null) {
            throw new IllegalArgumentException("A complete new bid source registration is required.");
        }
        if (registration.isExecutionEnabled()) {
            throw new IllegalArgumentException("New bid source registrations cannot be enabled.");
        }
    }

    private BidSourceRegistration map(ResultSet resultSet, int rowNumber) throws SQLException {
        return BidSourceRegistration.builder()
                .sourceId(resultSet.getLong("source_id"))
                .sourceName(resultSet.getString("source_name"))
                .siteUrl(resultSet.getString("site_url"))
                .sourceCode(resultSet.getString("source_code"))
                .registrationStatus(BidSourceRegistration.RegistrationStatus.valueOf(
                        resultSet.getString("registration_status")))
                .collectionMethod(BidSourceRegistration.CollectionMethod.valueOf(
                        resultSet.getString("collection_method")))
                .executionEnabled(resultSet.getBoolean("execution_enabled"))
                .checkStatus(BidSourceRegistration.CheckStatus.valueOf(resultSet.getString("check_status")))
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.valueOf(
                        resultSet.getString("detected_collection_method")))
                .httpStatus(nullableInteger(resultSet, "http_status"))
                .contentType(resultSet.getString("content_type"))
                .checkedAt(nullableInstant(resultSet, "checked_at"))
                .safeFailureCode(nullableFailureCode(resultSet.getString("safe_failure_code")))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .updatedAt(resultSet.getTimestamp("updated_at").toInstant())
                .build();
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private BidSourceRegistration.SafeFailureCode nullableFailureCode(String value) {
        return value == null ? null : BidSourceRegistration.SafeFailureCode.valueOf(value);
    }
}
