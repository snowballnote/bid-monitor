package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReview;
import com.comhu.bidmonitor.bid.persistence.BidSourceDiscoveryReviewRepository;
import com.comhu.bidmonitor.persistence.jdbc.JdbcDatabaseDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class JdbcBidSourceDiscoveryReviewRepository implements BidSourceDiscoveryReviewRepository {

    private static final String COLUMNS = """
            source_id, review_status, list_page_url, detail_url_pattern,
            identifier_mapping, title_mapping, agency_mapping, published_date_mapping,
            deadline_mapping, status_mapping, attachment_mapping, pagination_mapping, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDatabaseDialect dialect;

    public JdbcBidSourceDiscoveryReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = new JdbcDatabaseDialect(jdbcTemplate);
    }

    @Override
    @Transactional
    public BidSourceDiscoveryReview save(BidSourceDiscoveryReview review) {
        Object[] values = values(review);
        if (dialect.isPostgresql()) {
            jdbcTemplate.update("""
                    INSERT INTO bid_source_discovery_review (
                        source_id, review_status, list_page_url, detail_url_pattern,
                        identifier_mapping, title_mapping, agency_mapping, published_date_mapping,
                        deadline_mapping, status_mapping, attachment_mapping, pagination_mapping, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source_id) DO UPDATE SET
                        review_status = EXCLUDED.review_status,
                        list_page_url = EXCLUDED.list_page_url,
                        detail_url_pattern = EXCLUDED.detail_url_pattern,
                        identifier_mapping = EXCLUDED.identifier_mapping,
                        title_mapping = EXCLUDED.title_mapping,
                        agency_mapping = EXCLUDED.agency_mapping,
                        published_date_mapping = EXCLUDED.published_date_mapping,
                        deadline_mapping = EXCLUDED.deadline_mapping,
                        status_mapping = EXCLUDED.status_mapping,
                        attachment_mapping = EXCLUDED.attachment_mapping,
                        pagination_mapping = EXCLUDED.pagination_mapping,
                        updated_at = EXCLUDED.updated_at
                    """, values);
        } else {
            int updated = jdbcTemplate.update("""
                    UPDATE bid_source_discovery_review SET
                        review_status = ?, list_page_url = ?, detail_url_pattern = ?,
                        identifier_mapping = ?, title_mapping = ?, agency_mapping = ?,
                        published_date_mapping = ?, deadline_mapping = ?, status_mapping = ?,
                        attachment_mapping = ?, pagination_mapping = ?, updated_at = ?
                    WHERE source_id = ?
                    """, updateValues(review));
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO bid_source_discovery_review (
                            source_id, review_status, list_page_url, detail_url_pattern,
                            identifier_mapping, title_mapping, agency_mapping, published_date_mapping,
                            deadline_mapping, status_mapping, attachment_mapping, pagination_mapping, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, values);
            }
        }
        return findBySourceId(review.getSourceId()).orElseThrow();
    }

    @Override
    public Optional<BidSourceDiscoveryReview> findBySourceId(long sourceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM bid_source_discovery_review WHERE source_id = ?",
                this::map,
                sourceId
        ).stream().findFirst();
    }

    private Object[] values(BidSourceDiscoveryReview review) {
        return new Object[]{
                review.getSourceId(), review.getReviewStatus().name(), review.getListPageUrl(),
                review.getDetailUrlPattern(), review.getIdentifierMapping(), review.getTitleMapping(),
                review.getAgencyMapping(), review.getPublishedDateMapping(), review.getDeadlineMapping(),
                review.getStatusMapping(), review.getAttachmentMapping(), review.getPaginationMapping(),
                Timestamp.from(review.getUpdatedAt())
        };
    }

    private Object[] updateValues(BidSourceDiscoveryReview review) {
        Object[] insert = values(review);
        Object[] update = new Object[13];
        System.arraycopy(insert, 1, update, 0, 12);
        update[12] = review.getSourceId();
        return update;
    }

    private BidSourceDiscoveryReview map(ResultSet rs, int rowNumber) throws SQLException {
        return BidSourceDiscoveryReview.builder()
                .sourceId(rs.getLong("source_id"))
                .reviewStatus(BidSourceDiscoveryReview.ReviewStatus.valueOf(rs.getString("review_status")))
                .listPageUrl(rs.getString("list_page_url"))
                .detailUrlPattern(rs.getString("detail_url_pattern"))
                .identifierMapping(rs.getString("identifier_mapping"))
                .titleMapping(rs.getString("title_mapping"))
                .agencyMapping(rs.getString("agency_mapping"))
                .publishedDateMapping(rs.getString("published_date_mapping"))
                .deadlineMapping(rs.getString("deadline_mapping"))
                .statusMapping(rs.getString("status_mapping"))
                .attachmentMapping(rs.getString("attachment_mapping"))
                .paginationMapping(rs.getString("pagination_mapping"))
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
    }
}
