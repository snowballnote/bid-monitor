package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistrationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcBidSourceRegistrationRepository implements BidSourceRegistrationRepository {

    private static final String COLUMNS = """
            source_id, source_name, site_url, registration_status, collection_method,
            execution_enabled, created_at, updated_at
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
                        execution_enabled, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, registration.getSourceName());
            statement.setString(2, registration.getSiteUrl());
            statement.setString(3, registration.getRegistrationStatus().name());
            statement.setString(4, registration.getCollectionMethod().name());
            statement.setBoolean(5, registration.isExecutionEnabled());
            statement.setTimestamp(6, Timestamp.from(registration.getCreatedAt()));
            statement.setTimestamp(7, Timestamp.from(registration.getUpdatedAt()));
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
                || registration.getSourceName() == null || registration.getSourceName().isBlank()
                || registration.getSiteUrl() == null || registration.getSiteUrl().isBlank()
                || registration.getRegistrationStatus() == null
                || registration.getCollectionMethod() == null
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
                .registrationStatus(BidSourceRegistration.RegistrationStatus.valueOf(
                        resultSet.getString("registration_status")))
                .collectionMethod(BidSourceRegistration.CollectionMethod.valueOf(
                        resultSet.getString("collection_method")))
                .executionEnabled(resultSet.getBoolean("execution_enabled"))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .updatedAt(resultSet.getTimestamp("updated_at").toInstant())
                .build();
    }
}
