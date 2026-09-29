package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationAudit;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Objects;

@Repository
public class JdbcBidSourceRegistrationAuditRepository implements BidSourceRegistrationAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSourceRegistrationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(BidSourceRegistrationAudit audit) {
        Objects.requireNonNull(audit, "Bid source registration audit is required.");
        if (audit.actor() == null || audit.actor().isBlank()) {
            throw new IllegalArgumentException("Audit actor is required.");
        }
        jdbcTemplate.update("""
                        INSERT INTO bid_source_registration_audit (
                            source_id, action, previous_status, new_status,
                            previous_source_code, new_source_code,
                            previous_execution_enabled, new_execution_enabled,
                            actor, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                audit.sourceId(),
                audit.action().name(),
                enumName(audit.previousStatus()),
                enumName(audit.newStatus()),
                audit.previousSourceCode(),
                audit.newSourceCode(),
                audit.previousExecutionEnabled(),
                audit.newExecutionEnabled(),
                audit.actor().trim(),
                Timestamp.from(audit.createdAt())
        );
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
