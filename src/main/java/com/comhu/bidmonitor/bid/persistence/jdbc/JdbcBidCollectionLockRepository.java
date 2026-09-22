package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidCollectionLock;
import com.comhu.bidmonitor.bid.persistence.BidCollectionLockRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class JdbcBidCollectionLockRepository implements BidCollectionLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidCollectionLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BidCollectionLock> findForUpdate(
            String sourceCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return jdbcTemplate.query("""
                SELECT source_code, query_start_date, query_end_date, owner_token,
                       acquired_at, lease_expires_at, run_id
                FROM bid_collection_lock
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ?
                FOR UPDATE
                """, this::map, sourceCode, Date.valueOf(startDate), Date.valueOf(endDate))
                .stream().findFirst();
    }

    @Override
    public Optional<BidCollectionLock> find(String sourceCode, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query("""
                SELECT source_code, query_start_date, query_end_date, owner_token,
                       acquired_at, lease_expires_at, run_id
                FROM bid_collection_lock
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ?
                """, this::map, sourceCode, Date.valueOf(startDate), Date.valueOf(endDate))
                .stream().findFirst();
    }

    @Override
    public void insert(BidCollectionLock lock) {
        jdbcTemplate.update("""
                INSERT INTO bid_collection_lock (
                    source_code, query_start_date, query_end_date, owner_token,
                    acquired_at, lease_expires_at, run_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, lock.sourceCode(), Date.valueOf(lock.queryStartDate()), Date.valueOf(lock.queryEndDate()),
                lock.ownerToken(), Timestamp.from(lock.acquiredAt()), Timestamp.from(lock.leaseExpiresAt()),
                lock.runId());
    }

    @Override
    public void replace(BidCollectionLock lock) {
        int updated = jdbcTemplate.update("""
                UPDATE bid_collection_lock
                SET owner_token = ?, acquired_at = ?, lease_expires_at = ?, run_id = ?
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ?
                """, lock.ownerToken(), Timestamp.from(lock.acquiredAt()), Timestamp.from(lock.leaseExpiresAt()),
                lock.runId(), lock.sourceCode(), Date.valueOf(lock.queryStartDate()),
                Date.valueOf(lock.queryEndDate()));
        requireOne(updated, "replace");
    }

    @Override
    public void attachRun(
            String sourceCode,
            LocalDate startDate,
            LocalDate endDate,
            String ownerToken,
            long runId
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE bid_collection_lock SET run_id = ?
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ? AND owner_token = ?
                """, runId, sourceCode, Date.valueOf(startDate), Date.valueOf(endDate), ownerToken);
        requireOne(updated, "attach run");
    }

    @Override
    public boolean renew(
            String sourceCode,
            LocalDate startDate,
            LocalDate endDate,
            String ownerToken,
            Instant leaseExpiresAt
    ) {
        return jdbcTemplate.update("""
                UPDATE bid_collection_lock SET lease_expires_at = ?
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ? AND owner_token = ?
                """, Timestamp.from(leaseExpiresAt), sourceCode, Date.valueOf(startDate),
                Date.valueOf(endDate), ownerToken) == 1;
    }

    @Override
    public boolean release(String sourceCode, LocalDate startDate, LocalDate endDate, String ownerToken) {
        return jdbcTemplate.update("""
                DELETE FROM bid_collection_lock
                WHERE source_code = ? AND query_start_date = ? AND query_end_date = ? AND owner_token = ?
                """, sourceCode, Date.valueOf(startDate), Date.valueOf(endDate), ownerToken) == 1;
    }

    private BidCollectionLock map(ResultSet rs, int rowNumber) throws SQLException {
        Number runId = (Number) rs.getObject("run_id");
        return new BidCollectionLock(
                rs.getString("source_code"),
                rs.getDate("query_start_date").toLocalDate(),
                rs.getDate("query_end_date").toLocalDate(),
                rs.getString("owner_token"),
                rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("lease_expires_at").toInstant(),
                runId == null ? null : runId.longValue()
        );
    }

    private void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Bid collection lock " + operation + " did not affect one row.");
        }
    }
}
