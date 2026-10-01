package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResult;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryResultRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceRegistration;
import com.comhu.bidmonitor.persistence.jdbc.JdbcDatabaseDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcBidSourceDiscoveryResultRepository implements BidSourceDiscoveryResultRepository {

    private static final String COLUMNS = """
            source_id, discovery_status, detected_collection_method, list_page_url,
            detail_url_pattern, identifier_confidence, title_confidence, deadline_confidence,
            attachment_detected, pagination_detected, reason_codes, analyzed_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDatabaseDialect dialect;

    public JdbcBidSourceDiscoveryResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = new JdbcDatabaseDialect(jdbcTemplate);
    }

    @Override
    @Transactional
    public BidSourceDiscoveryResult save(BidSourceDiscoveryResult result) {
        Object[] values = values(result);
        if (dialect.isPostgresql()) {
            jdbcTemplate.update("""
                    INSERT INTO bid_source_discovery_result (
                        source_id, discovery_status, detected_collection_method, list_page_url,
                        detail_url_pattern, identifier_confidence, title_confidence, deadline_confidence,
                        attachment_detected, pagination_detected, reason_codes, analyzed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source_id) DO UPDATE SET
                        discovery_status = EXCLUDED.discovery_status,
                        detected_collection_method = EXCLUDED.detected_collection_method,
                        list_page_url = EXCLUDED.list_page_url,
                        detail_url_pattern = EXCLUDED.detail_url_pattern,
                        identifier_confidence = EXCLUDED.identifier_confidence,
                        title_confidence = EXCLUDED.title_confidence,
                        deadline_confidence = EXCLUDED.deadline_confidence,
                        attachment_detected = EXCLUDED.attachment_detected,
                        pagination_detected = EXCLUDED.pagination_detected,
                        reason_codes = EXCLUDED.reason_codes,
                        analyzed_at = EXCLUDED.analyzed_at
                    """, values);
        } else {
            int updated = jdbcTemplate.update("""
                    UPDATE bid_source_discovery_result SET
                        discovery_status = ?, detected_collection_method = ?, list_page_url = ?,
                        detail_url_pattern = ?, identifier_confidence = ?, title_confidence = ?,
                        deadline_confidence = ?, attachment_detected = ?, pagination_detected = ?,
                        reason_codes = ?, analyzed_at = ?
                    WHERE source_id = ?
                    """, updateValues(result));
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO bid_source_discovery_result (
                            source_id, discovery_status, detected_collection_method, list_page_url,
                            detail_url_pattern, identifier_confidence, title_confidence, deadline_confidence,
                            attachment_detected, pagination_detected, reason_codes, analyzed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, values);
            }
        }
        return findBySourceId(result.getSourceId()).orElseThrow();
    }

    @Override
    public Optional<BidSourceDiscoveryResult> findBySourceId(long sourceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_discovery_result WHERE source_id = ?",
                this::map,
                sourceId
        ).stream().findFirst();
    }

    private Object[] values(BidSourceDiscoveryResult result) {
        return new Object[]{
                result.getSourceId(), result.getDiscoveryStatus().name(),
                result.getDetectedCollectionMethod().name(), result.getListPageUrl(),
                result.getDetailUrlPattern(), result.getIdentifierConfidence().name(),
                result.getTitleConfidence().name(), result.getDeadlineConfidence().name(),
                result.isAttachmentDetected(), result.isPaginationDetected(),
                String.join(",", result.getReasonCodes()), Timestamp.from(result.getAnalyzedAt())
        };
    }

    private Object[] updateValues(BidSourceDiscoveryResult result) {
        Object[] insert = values(result);
        Object[] update = new Object[12];
        System.arraycopy(insert, 1, update, 0, 11);
        update[11] = result.getSourceId();
        return update;
    }

    private BidSourceDiscoveryResult map(ResultSet rs, int rowNumber) throws SQLException {
        String reasons = rs.getString("reason_codes");
        List<String> reasonCodes = reasons == null || reasons.isBlank()
                ? List.of()
                : Arrays.stream(reasons.split(",")).filter(value -> !value.isBlank()).toList();
        return BidSourceDiscoveryResult.builder()
                .sourceId(rs.getLong("source_id"))
                .discoveryStatus(BidSourceDiscoveryResult.DiscoveryStatus.valueOf(rs.getString("discovery_status")))
                .detectedCollectionMethod(BidSourceRegistration.CollectionMethod.valueOf(
                        rs.getString("detected_collection_method")))
                .listPageUrl(rs.getString("list_page_url"))
                .detailUrlPattern(rs.getString("detail_url_pattern"))
                .identifierConfidence(BidSourceDiscoveryResult.Confidence.valueOf(
                        rs.getString("identifier_confidence")))
                .titleConfidence(BidSourceDiscoveryResult.Confidence.valueOf(rs.getString("title_confidence")))
                .deadlineConfidence(BidSourceDiscoveryResult.Confidence.valueOf(rs.getString("deadline_confidence")))
                .attachmentDetected(rs.getBoolean("attachment_detected"))
                .paginationDetected(rs.getBoolean("pagination_detected"))
                .reasonCodes(reasonCodes)
                .analyzedAt(rs.getTimestamp("analyzed_at").toInstant())
                .build();
    }
}
