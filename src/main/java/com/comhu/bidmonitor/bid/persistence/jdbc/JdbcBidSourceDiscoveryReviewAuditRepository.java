package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewAudit;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class JdbcBidSourceDiscoveryReviewAuditRepository implements BidSourceDiscoveryReviewAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceDiscoveryReviewAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(BidSourceDiscoveryReviewAudit audit) {
        if (audit.actor() == null || audit.actor().isBlank()) {
            throw new IllegalArgumentException("Audit actor is required.");
        }
        jdbcTemplate.update("""
                INSERT INTO bid_source_discovery_review_audit (
                    source_id, action, previous_status, new_status, actor, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                audit.sourceId(), audit.action().name(), audit.previousStatus().name(),
                audit.newStatus().name(), audit.actor().trim(), Timestamp.from(audit.createdAt()));
    }
}
