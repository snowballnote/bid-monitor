package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidCollectionRun;
import com.comhu.bidmonitor.bid.persistence.BidCollectionRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcBidCollectionRunRepository implements BidCollectionRunRepository {

    private static final String COLUMNS = """
            id, source_code, trigger_type, query_start_date, query_end_date, started_at, finished_at,
            status, api_call_count, collected_count, new_count, changed_count, failure_count, error_code
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidCollectionRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BidCollectionRun save(BidCollectionRun run) {
        validate(run);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bid_collection_run (
                        source_code, trigger_type, query_start_date, query_end_date, started_at,
                        finished_at, status, api_call_count, collected_count, new_count,
                        changed_count, failure_count, error_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, run.getSourceCode().trim());
            statement.setString(2, run.getTriggerType().name());
            statement.setDate(3, Date.valueOf(run.getQueryStartDate()));
            statement.setDate(4, Date.valueOf(run.getQueryEndDate()));
            statement.setTimestamp(5, Timestamp.from(run.getStartedAt()));
            setTimestamp(statement, 6, run.getFinishedAt());
            statement.setString(7, run.getStatus().name());
            setInteger(statement, 8, run.getApiCallCount());
            statement.setInt(9, run.getCollectedCount());
            statement.setInt(10, run.getNewCount());
            statement.setInt(11, run.getChangedCount());
            statement.setInt(12, run.getFailureCount());
            statement.setString(13, run.getErrorCode());
            return statement;
        }, keyHolder);
        Long id = keyHolder.getKeyAs(Long.class);
        return findById(id).orElseThrow(() -> new IllegalStateException("Saved collection run was not found."));
    }

    @Override
    public BidCollectionRun update(BidCollectionRun run) {
        validate(run);
        if (run.getId() == null) {
            throw new IllegalArgumentException("Collection run id is required for update.");
        }
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bid_collection_run SET
                        finished_at = ?, status = ?, api_call_count = ?, collected_count = ?,
                        new_count = ?, changed_count = ?, failure_count = ?, error_code = ?
                    WHERE id = ? AND source_code = ?
                    """);
            setTimestamp(statement, 1, run.getFinishedAt());
            statement.setString(2, run.getStatus().name());
            setInteger(statement, 3, run.getApiCallCount());
            statement.setInt(4, run.getCollectedCount());
            statement.setInt(5, run.getNewCount());
            statement.setInt(6, run.getChangedCount());
            statement.setInt(7, run.getFailureCount());
            statement.setString(8, run.getErrorCode());
            statement.setLong(9, run.getId());
            statement.setString(10, run.getSourceCode().trim());
            return statement;
        });
        if (updated != 1) {
            throw new IllegalStateException("Collection run update did not affect exactly one row: " + run.getId());
        }
        return findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("Updated collection run was not found."));
    }

    @Override
    public Optional<BidCollectionRun> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_collection_run WHERE id = ?",
                this::map,
                id
        ).stream().findFirst();
    }

    @Override
    public List<BidCollectionRun> findBySourceCodeLatestFirst(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode is required.");
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM bid_collection_run WHERE source_code = ? ORDER BY started_at DESC, id DESC",
                this::map,
                sourceCode.trim()
        );
    }

    private void validate(BidCollectionRun run) {
        Objects.requireNonNull(run, "Collection run is required.");
        if (run.getSourceCode() == null || run.getSourceCode().isBlank()) {
            throw new IllegalArgumentException("sourceCode is required.");
        }
        Objects.requireNonNull(run.getTriggerType(), "triggerType is required.");
        Objects.requireNonNull(run.getQueryStartDate(), "queryStartDate is required.");
        Objects.requireNonNull(run.getQueryEndDate(), "queryEndDate is required.");
        Objects.requireNonNull(run.getStartedAt(), "startedAt is required.");
        Objects.requireNonNull(run.getStatus(), "status is required.");
        if (run.getQueryEndDate().isBefore(run.getQueryStartDate())) {
            throw new IllegalArgumentException("queryEndDate must not be before queryStartDate.");
        }
        if (run.getFinishedAt() != null && run.getFinishedAt().isBefore(run.getStartedAt())) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt.");
        }
        if ((run.getApiCallCount() != null && run.getApiCallCount() < 0)
                || run.getCollectedCount() < 0 || run.getNewCount() < 0
                || run.getChangedCount() < 0 || run.getFailureCount() < 0) {
            throw new IllegalArgumentException("Collection counts must not be negative.");
        }
    }

    private BidCollectionRun map(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return BidCollectionRun.builder()
                .id(rs.getLong("id"))
                .sourceCode(rs.getString("source_code"))
                .triggerType(BidCollectionRun.TriggerType.valueOf(rs.getString("trigger_type")))
                .queryStartDate(rs.getDate("query_start_date").toLocalDate())
                .queryEndDate(rs.getDate("query_end_date").toLocalDate())
                .startedAt(rs.getTimestamp("started_at").toInstant())
                .finishedAt(finishedAt == null ? null : finishedAt.toInstant())
                .status(BidCollectionRun.Status.valueOf(rs.getString("status")))
                .apiCallCount((Integer) rs.getObject("api_call_count"))
                .collectedCount(rs.getInt("collected_count"))
                .newCount(rs.getInt("new_count"))
                .changedCount(rs.getInt("changed_count"))
                .failureCount(rs.getInt("failure_count"))
                .errorCode(rs.getString("error_code"))
                .build();
    }

    private void setTimestamp(PreparedStatement statement, int index, java.time.Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
