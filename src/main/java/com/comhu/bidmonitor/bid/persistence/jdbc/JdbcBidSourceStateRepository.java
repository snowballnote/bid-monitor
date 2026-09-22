package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceState;
import com.comhu.bidmonitor.bid.persistence.BidSourceStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcBidSourceStateRepository implements BidSourceStateRepository {

    private static final String COLUMNS = """
            source_code, last_attempt_at, last_success_at, last_failure_at, consecutive_failures,
            next_run_at, daily_limit, used_calls, quota_date, cooldown_until, cooldown_seconds,
            last_error_code
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public BidSourceState save(BidSourceState state) {
        validate(state);
        String sourceCode = state.getSourceCode().trim();
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bid_source_state SET
                        last_attempt_at = ?, last_success_at = ?, last_failure_at = ?,
                        consecutive_failures = ?, next_run_at = ?, daily_limit = ?, used_calls = ?,
                        quota_date = ?, cooldown_until = ?, cooldown_seconds = ?, last_error_code = ?
                    WHERE source_code = ?
                    """);
            setStateValues(statement, state);
            statement.setString(12, sourceCode);
            return statement;
        });
        if (updated == 0) {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bid_source_state (
                            last_attempt_at, last_success_at, last_failure_at, consecutive_failures,
                            next_run_at, daily_limit, used_calls, quota_date, cooldown_until,
                            cooldown_seconds, last_error_code, source_code
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                setStateValues(statement, state);
                statement.setString(12, sourceCode);
                return statement;
            });
        }
        return findBySourceCode(sourceCode)
                .orElseThrow(() -> new IllegalStateException("Saved bid source state was not found."));
    }

    @Override
    public Optional<BidSourceState> findBySourceCode(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode is required.");
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_state WHERE source_code = ?",
                this::map,
                sourceCode.trim()
        ).stream().findFirst();
    }

    @Override
    public List<BidSourceState> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_state ORDER BY source_code",
                this::map
        );
    }

    @Override
    @Transactional
    public boolean tryReserveDailyCall(String sourceCode, LocalDate quotaDate, int defaultDailyLimit) {
        if (sourceCode == null || sourceCode.isBlank() || quotaDate == null) {
            throw new IllegalArgumentException("sourceCode and quotaDate are required.");
        }
        if (defaultDailyLimit < 0) {
            throw new IllegalArgumentException("defaultDailyLimit must not be negative.");
        }
        String normalizedSourceCode = sourceCode.trim();
        Date sqlDate = Date.valueOf(quotaDate);

        int reservedAfterDateChange = jdbcTemplate.update("""
                UPDATE bid_source_state
                SET daily_limit = COALESCE(daily_limit, ?), used_calls = 1, quota_date = ?
                WHERE source_code = ?
                  AND (quota_date IS NULL OR quota_date <> ?)
                  AND COALESCE(daily_limit, ?) > 0
                """, defaultDailyLimit, sqlDate, normalizedSourceCode, sqlDate, defaultDailyLimit);
        if (reservedAfterDateChange == 1) {
            return true;
        }

        int reservedForCurrentDate = jdbcTemplate.update("""
                UPDATE bid_source_state
                SET daily_limit = COALESCE(daily_limit, ?), used_calls = used_calls + 1
                WHERE source_code = ?
                  AND quota_date = ?
                  AND used_calls < COALESCE(daily_limit, ?)
                """, defaultDailyLimit, normalizedSourceCode, sqlDate, defaultDailyLimit);
        return reservedForCurrentDate == 1;
    }

    private void validate(BidSourceState state) {
        Objects.requireNonNull(state, "Bid source state is required.");
        if (state.getSourceCode() == null || state.getSourceCode().isBlank()) {
            throw new IllegalArgumentException("sourceCode is required.");
        }
        if (state.getConsecutiveFailures() < 0 || state.getUsedCalls() < 0
                || state.getCooldownSeconds() < 0
                || (state.getDailyLimit() != null && state.getDailyLimit() < 0)) {
            throw new IllegalArgumentException("Source counters and limits must not be negative.");
        }
    }

    private void setStateValues(PreparedStatement statement, BidSourceState state) throws SQLException {
        setTimestamp(statement, 1, state.getLastAttemptAt());
        setTimestamp(statement, 2, state.getLastSuccessAt());
        setTimestamp(statement, 3, state.getLastFailureAt());
        statement.setInt(4, state.getConsecutiveFailures());
        setTimestamp(statement, 5, state.getNextRunAt());
        if (state.getDailyLimit() == null) {
            statement.setNull(6, Types.INTEGER);
        } else {
            statement.setInt(6, state.getDailyLimit());
        }
        statement.setInt(7, state.getUsedCalls());
        if (state.getQuotaDate() == null) {
            statement.setNull(8, Types.DATE);
        } else {
            statement.setDate(8, Date.valueOf(state.getQuotaDate()));
        }
        setTimestamp(statement, 9, state.getCooldownUntil());
        statement.setLong(10, state.getCooldownSeconds());
        statement.setString(11, state.getLastErrorCode());
    }

    private BidSourceState map(ResultSet rs, int rowNumber) throws SQLException {
        Integer dailyLimit = (Integer) rs.getObject("daily_limit");
        Date quotaDate = rs.getDate("quota_date");
        return BidSourceState.builder()
                .sourceCode(rs.getString("source_code"))
                .lastAttemptAt(instant(rs, "last_attempt_at"))
                .lastSuccessAt(instant(rs, "last_success_at"))
                .lastFailureAt(instant(rs, "last_failure_at"))
                .consecutiveFailures(rs.getInt("consecutive_failures"))
                .nextRunAt(instant(rs, "next_run_at"))
                .dailyLimit(dailyLimit)
                .usedCalls(rs.getInt("used_calls"))
                .quotaDate(quotaDate == null ? null : quotaDate.toLocalDate())
                .cooldownUntil(instant(rs, "cooldown_until"))
                .cooldownSeconds(rs.getLong("cooldown_seconds"))
                .lastErrorCode(rs.getString("last_error_code"))
                .build();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }
}
