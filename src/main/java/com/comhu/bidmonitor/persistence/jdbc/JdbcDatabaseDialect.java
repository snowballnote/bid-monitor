package com.comhu.bidmonitor.persistence.jdbc;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.Locale;

/** Limits database-specific SQL to the repositories that need atomic upsert semantics. */
public final class JdbcDatabaseDialect {

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean resolved;
    private volatile boolean postgresql;

    public JdbcDatabaseDialect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isPostgresql() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    postgresql = Boolean.TRUE.equals(jdbcTemplate.execute(
                            (ConnectionCallback<Boolean>) connection -> isPostgresql(connection.getMetaData().getDatabaseProductName())
                    ));
                    resolved = true;
                }
            }
        }
        return postgresql;
    }

    private boolean isPostgresql(String productName) throws SQLException {
        if (productName == null) {
            throw new SQLException("Database product name is unavailable.");
        }
        return productName.toLowerCase(Locale.ROOT).contains("postgresql");
    }
}
