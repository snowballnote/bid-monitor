package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.AllowedIndustryCodeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class JdbcAllowedIndustryCodeRepository implements AllowedIndustryCodeRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAllowedIndustryCodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<String> findAll() {
        LinkedHashSet<String> codes = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT code FROM allowed_industry_code ORDER BY code", String.class
        ));
        return Collections.unmodifiableSet(codes);
    }

    @Override
    @Transactional
    public Set<String> replaceAll(Set<String> codes) {
        jdbcTemplate.update("DELETE FROM allowed_industry_code");
        Instant createdAt = Instant.now();
        jdbcTemplate.batchUpdate(
                "INSERT INTO allowed_industry_code (code, created_at) VALUES (?, ?)",
                codes,
                codes.size(),
                (statement, code) -> {
                    statement.setString(1, code);
                    statement.setTimestamp(2, Timestamp.from(createdAt));
                }
        );
        return findAll();
    }
}
