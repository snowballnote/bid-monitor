package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetry;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionRetryRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcBidSourceCollectionRetryRepository implements BidSourceCollectionRetryRepository {

    private static final String COLUMNS = """
            id, source_code, scope, operation, window_start, window_end, page_number,
            source_notice_id, revision, payload, retry_count, last_error_code, last_attempt_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceCollectionRetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BidSourceCollectionRetry savePending(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageNumber,
            String sourceNoticeId,
            String revision,
            String payload,
            Instant now
    ) {
        validate(sourceCode, scope, operation, windowStart, windowEnd, pageNumber,
                sourceNoticeId, payload, now);
        String normalizedRevision = revision == null ? "" : revision.trim();
        int updated = updatePayload(
                sourceCode, scope, operation, windowStart, windowEnd, pageNumber,
                sourceNoticeId, normalizedRevision, payload, now
        );
        if (updated == 0) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO bid_source_collection_retry (
                            source_code, scope, operation, window_start, window_end, page_number,
                            source_notice_id, revision, payload, retry_count, last_error_code,
                            last_attempt_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, ?, ?)
                        """, sourceCode.trim(), scope.trim(), operation.trim(), Date.valueOf(windowStart),
                        Date.valueOf(windowEnd), pageNumber, sourceNoticeId.trim(), normalizedRevision,
                        payload, Timestamp.from(now), Timestamp.from(now));
            } catch (DuplicateKeyException ignored) {
                updatePayload(sourceCode, scope, operation, windowStart, windowEnd, pageNumber,
                        sourceNoticeId, normalizedRevision, payload, now);
            }
        }
        return find(sourceCode, scope, operation, windowStart, windowEnd,
                sourceNoticeId, normalizedRevision).orElseThrow(
                () -> new IllegalStateException("Collection retry was not saved."));
    }

    @Override
    public List<BidSourceCollectionRetry> findPending(String sourceCode, String scope) {
        if (sourceCode == null || sourceCode.isBlank() || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("sourceCode and scope are required.");
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_collection_retry "
                        + "WHERE source_code = ? AND scope = ? "
                        + "ORDER BY created_at, id",
                this::map, sourceCode.trim(), scope.trim()
        );
    }

    @Override
    public void recordFailure(long id, String errorCode, Instant attemptedAt) {
        if (id <= 0 || attemptedAt == null) {
            throw new IllegalArgumentException("Invalid collection retry failure.");
        }
        jdbcTemplate.update("""
                UPDATE bid_source_collection_retry
                SET retry_count = retry_count + 1, last_error_code = ?, last_attempt_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, errorCode, Timestamp.from(attemptedAt), Timestamp.from(attemptedAt), id);
    }

    @Override
    public void delete(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Collection retry id must be positive.");
        }
        jdbcTemplate.update("DELETE FROM bid_source_collection_retry WHERE id = ?", id);
    }

    private int updatePayload(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageNumber,
            String sourceNoticeId,
            String revision,
            String payload,
            Instant now
    ) {
        return jdbcTemplate.update("""
                UPDATE bid_source_collection_retry
                SET page_number = ?, payload = ?, updated_at = ?
                WHERE source_code = ? AND scope = ? AND operation = ?
                  AND window_start = ? AND window_end = ?
                  AND source_notice_id = ? AND revision = ?
                """, pageNumber, payload, Timestamp.from(now), sourceCode.trim(), scope.trim(),
                operation.trim(), Date.valueOf(windowStart), Date.valueOf(windowEnd),
                sourceNoticeId.trim(), revision);
    }

    private Optional<BidSourceCollectionRetry> find(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            String sourceNoticeId,
            String revision
    ) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_collection_retry "
                        + "WHERE source_code = ? AND scope = ? AND operation = ? "
                        + "AND window_start = ? AND window_end = ? "
                        + "AND source_notice_id = ? AND revision = ?",
                this::map, sourceCode.trim(), scope.trim(), operation.trim(), Date.valueOf(windowStart),
                Date.valueOf(windowEnd), sourceNoticeId.trim(), revision
        ).stream().findFirst();
    }

    private BidSourceCollectionRetry map(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp lastAttempt = rs.getTimestamp("last_attempt_at");
        return new BidSourceCollectionRetry(
                rs.getLong("id"), rs.getString("source_code"), rs.getString("scope"),
                rs.getString("operation"), rs.getDate("window_start").toLocalDate(),
                rs.getDate("window_end").toLocalDate(), rs.getInt("page_number"),
                rs.getString("source_notice_id"), rs.getString("revision"), rs.getString("payload"),
                rs.getInt("retry_count"), rs.getString("last_error_code"),
                lastAttempt == null ? null : lastAttempt.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static void validate(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageNumber,
            String sourceNoticeId,
            String payload,
            Instant now
    ) {
        if (sourceCode == null || sourceCode.isBlank() || scope == null || scope.isBlank()
                || operation == null || operation.isBlank() || windowStart == null || windowEnd == null
                || windowStart.isAfter(windowEnd) || pageNumber <= 0
                || sourceNoticeId == null || sourceNoticeId.isBlank()
                || payload == null || payload.isBlank() || now == null) {
            throw new IllegalArgumentException("Invalid collection retry.");
        }
    }
}
