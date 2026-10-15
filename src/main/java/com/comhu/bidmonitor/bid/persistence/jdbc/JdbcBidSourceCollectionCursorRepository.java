package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursor;
import com.comhu.bidmonitor.bid.persistence.BidSourceCollectionCursorRepository;
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
public class JdbcBidSourceCollectionCursorRepository implements BidSourceCollectionCursorRepository {

    private static final String COLUMNS = """
            id, source_code, scope, operation, window_start, window_end, next_page, page_size,
            total_count_snapshot, status, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceCollectionCursorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BidSourceCollectionCursor getOrCreate(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageSize,
            Instant now
    ) {
        validateKey(sourceCode, scope, operation, windowStart, windowEnd, pageSize, now);
        Optional<BidSourceCollectionCursor> existing = find(
                sourceCode, scope, operation, windowStart, windowEnd
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO bid_source_collection_cursor (
                        source_code, scope, operation, window_start, window_end, next_page,
                        page_size, total_count_snapshot, status, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 1, ?, NULL, 'PENDING', 0, ?, ?)
                    """, sourceCode.trim(), scope.trim(), operation.trim(), Date.valueOf(windowStart),
                    Date.valueOf(windowEnd), pageSize, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // Another collector created the same natural-key cursor first.
        }
        return find(sourceCode, scope, operation, windowStart, windowEnd)
                .orElseThrow(() -> new IllegalStateException("Collection cursor was not created."));
    }

    @Override
    public List<BidSourceCollectionCursor> findIncomplete(String sourceCode, String scope) {
        if (sourceCode == null || sourceCode.isBlank() || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("sourceCode and scope are required.");
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_collection_cursor "
                        + "WHERE source_code = ? AND scope = ? AND status <> 'COMPLETE' "
                        + "ORDER BY window_end DESC, window_start DESC, operation, id",
                this::map,
                sourceCode.trim(), scope.trim()
        );
    }

    @Override
    public boolean reopenCompleted(long id, long expectedVersion, Instant updatedAt) {
        if (id <= 0 || expectedVersion < 0 || updatedAt == null) {
            throw new IllegalArgumentException("Invalid collection cursor reopen.");
        }
        return jdbcTemplate.update("""
                UPDATE bid_source_collection_cursor
                SET next_page = 1, total_count_snapshot = NULL, status = 'PENDING',
                    version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'COMPLETE'
                """, Timestamp.from(updatedAt), id, expectedVersion) == 1;
    }

    @Override
    public boolean advance(
            long id,
            long expectedVersion,
            int nextPage,
            Integer totalCountSnapshot,
            BidSourceCollectionCursor.Status status,
            Instant updatedAt
    ) {
        if (id <= 0 || expectedVersion < 0 || nextPage <= 0 || status == null || updatedAt == null
                || (totalCountSnapshot != null && totalCountSnapshot < 0)) {
            throw new IllegalArgumentException("Invalid collection cursor advance.");
        }
        return jdbcTemplate.update("""
                UPDATE bid_source_collection_cursor
                SET next_page = ?, total_count_snapshot = ?, status = ?, version = version + 1,
                    updated_at = ?
                WHERE id = ? AND version = ?
                """, nextPage, totalCountSnapshot, status.name(), Timestamp.from(updatedAt), id,
                expectedVersion) == 1;
    }

    private Optional<BidSourceCollectionCursor> find(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd
    ) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_collection_cursor "
                        + "WHERE source_code = ? AND scope = ? AND operation = ? "
                        + "AND window_start = ? AND window_end = ?",
                this::map,
                sourceCode.trim(), scope.trim(), operation.trim(), Date.valueOf(windowStart),
                Date.valueOf(windowEnd)
        ).stream().findFirst();
    }

    private BidSourceCollectionCursor map(ResultSet rs, int rowNumber) throws SQLException {
        Integer total = (Integer) rs.getObject("total_count_snapshot");
        return new BidSourceCollectionCursor(
                rs.getLong("id"), rs.getString("source_code"), rs.getString("scope"),
                rs.getString("operation"), rs.getDate("window_start").toLocalDate(),
                rs.getDate("window_end").toLocalDate(), rs.getInt("next_page"),
                rs.getInt("page_size"), total,
                BidSourceCollectionCursor.Status.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static void validateKey(
            String sourceCode,
            String scope,
            String operation,
            LocalDate windowStart,
            LocalDate windowEnd,
            int pageSize,
            Instant now
    ) {
        if (sourceCode == null || sourceCode.isBlank() || scope == null || scope.isBlank()
                || operation == null || operation.isBlank() || windowStart == null || windowEnd == null
                || windowStart.isAfter(windowEnd) || pageSize <= 0 || now == null) {
            throw new IllegalArgumentException("Invalid collection cursor key.");
        }
    }
}
