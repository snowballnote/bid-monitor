package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidNoticeSaveResult;
import com.comhu.bidmonitor.bid.persistence.BidNoticeVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class JdbcBidNoticeRepository implements BidNoticeRepository {

    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String NOTICE_COLUMNS = """
            id, source_code, source_notice_id, revision_key, notice_number, title,
            ordering_organization, published_at, submission_deadline_at, bid_opening_at,
            contract_method, bid_method, notice_status, notice_status_code, detail_url,
            relevant, analysis_status, analysis_result, content_hash, first_seen_at, last_seen_at
            """;
    private static final String VERSION_COLUMNS = """
            id, bid_notice_id, notice_number, title, ordering_organization, published_at,
            submission_deadline_at, bid_opening_at, contract_method, bid_method, notice_status,
            notice_status_code, detail_url, relevant, analysis_status, analysis_result,
            content_hash, first_seen_at, last_seen_at, captured_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidNoticeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public BidNoticeSaveResult save(BidNotice candidate) {
        BidNotice normalized = normalize(candidate);
        Optional<BidNotice> found = findByIdentityForUpdate(
                normalized.getSourceCode(),
                normalized.getSourceNoticeId(),
                normalized.getRevisionKey()
        );
        if (found.isEmpty()) {
            BidNotice inserted = insert(normalized);
            return new BidNoticeSaveResult(BidNoticeSaveResult.ChangeType.NEW, inserted, null);
        }

        BidNotice existing = found.get();
        Instant effectiveLastSeen = later(existing.getLastSeenAt(), normalized.getLastSeenAt());
        if (existing.getContentHash().equals(normalized.getContentHash())) {
            jdbcTemplate.update(
                    "UPDATE bid_notice SET last_seen_at = ? WHERE id = ?",
                    Timestamp.from(effectiveLastSeen),
                    existing.getId()
            );
            BidNotice unchanged = findById(existing.getId()).orElseThrow();
            return new BidNoticeSaveResult(
                    BidNoticeSaveResult.ChangeType.UNCHANGED,
                    unchanged,
                    existing.getContentHash()
            );
        }

        preserveCurrentVersion(existing, effectiveLastSeen);
        updateCurrent(existing.getId(), normalized, effectiveLastSeen);
        BidNotice updated = findById(existing.getId()).orElseThrow();
        return new BidNoticeSaveResult(
                BidNoticeSaveResult.ChangeType.UPDATED,
                updated,
                existing.getContentHash()
        );
    }

    @Override
    public Optional<BidNotice> findByIdentity(String sourceCode, String sourceNoticeId, String revision) {
        return queryIdentity(sourceCode, sourceNoticeId, normalizeRevision(revision), false);
    }

    @Override
    public Optional<BidNotice> findById(Long id) {
        Objects.requireNonNull(id, "Bid notice id is required.");
        return jdbcTemplate.query(
                "SELECT " + NOTICE_COLUMNS + " FROM bid_notice WHERE id = ?",
                this::mapNotice,
                id
        ).stream().findFirst();
    }

    @Override
    public List<BidNotice> findAll() {
        return jdbcTemplate.query(
                "SELECT " + NOTICE_COLUMNS + " FROM bid_notice ORDER BY id",
                this::mapNotice
        );
    }

    @Override
    public BidNoticePage findLatest(
            LocalDate startDate,
            LocalDate endDate,
            String sourceCode,
            int page,
            int size
    ) {
        StringBuilder where = new StringBuilder(" FROM bid_notice WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (startDate != null) {
            where.append(" AND published_at >= ?");
            parameters.add(Timestamp.valueOf(startDate.atStartOfDay()));
        }
        if (endDate != null) {
            where.append(" AND published_at <= ?");
            parameters.add(Timestamp.valueOf(endDate.atTime(LocalTime.MAX)));
        }
        if (sourceCode != null) {
            where.append(" AND source_code = ?");
            parameters.add(sourceCode);
        }

        Long totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + where,
                Long.class,
                parameters.toArray()
        );
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(Math.multiplyExact((long) page, size));
        List<BidNotice> items = jdbcTemplate.query(
                "SELECT " + NOTICE_COLUMNS + where
                        + " ORDER BY published_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?",
                this::mapNotice,
                pageParameters.toArray()
        );
        return new BidNoticePage(items, Objects.requireNonNull(totalCount));
    }

    @Override
    public List<BidNoticeVersion> findVersions(Long bidNoticeId) {
        Objects.requireNonNull(bidNoticeId, "Bid notice id is required.");
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS
                        + " FROM bid_notice_version WHERE bid_notice_id = ? ORDER BY id",
                this::mapVersion,
                bidNoticeId
        );
    }

    private Optional<BidNotice> findByIdentityForUpdate(
            String sourceCode,
            String sourceNoticeId,
            String revisionKey
    ) {
        return queryIdentity(sourceCode, sourceNoticeId, revisionKey, true);
    }

    private Optional<BidNotice> queryIdentity(
            String sourceCode,
            String sourceNoticeId,
            String revisionKey,
            boolean forUpdate
    ) {
        String normalizedSource = requireText(sourceCode, "sourceCode");
        String normalizedId = requireText(sourceNoticeId, "sourceNoticeId");
        String sql = "SELECT " + NOTICE_COLUMNS
                + " FROM bid_notice WHERE source_code = ? AND source_notice_id = ? AND revision_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, this::mapNotice, normalizedSource, normalizedId, revisionKey)
                .stream().findFirst();
    }

    private BidNotice insert(BidNotice notice) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bid_notice (
                        source_code, source_notice_id, revision_key, notice_number, title,
                        ordering_organization, published_at, submission_deadline_at, bid_opening_at,
                        contract_method, bid_method, notice_status, notice_status_code, detail_url,
                        relevant, analysis_status, analysis_result, content_hash, first_seen_at, last_seen_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            setCurrentValues(statement, notice, notice.getFirstSeenAt(), notice.getLastSeenAt());
            return statement;
        }, keyHolder);
        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("Saved bid notice id was not returned.");
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Saved bid notice was not found."));
    }

    private void preserveCurrentVersion(BidNotice existing, Instant capturedAt) {
        jdbcTemplate.update("""
                INSERT INTO bid_notice_version (
                    bid_notice_id, notice_number, title, ordering_organization, published_at,
                    submission_deadline_at, bid_opening_at, contract_method, bid_method, notice_status,
                    notice_status_code, detail_url, relevant, analysis_status, analysis_result,
                    content_hash, first_seen_at, last_seen_at, captured_at
                )
                SELECT id, notice_number, title, ordering_organization, published_at,
                    submission_deadline_at, bid_opening_at, contract_method, bid_method, notice_status,
                    notice_status_code, detail_url, relevant, analysis_status, analysis_result,
                    content_hash, first_seen_at, last_seen_at, ?
                FROM bid_notice current_notice
                WHERE id = ? AND NOT EXISTS (
                    SELECT 1 FROM bid_notice_version previous_version
                    WHERE previous_version.bid_notice_id = current_notice.id
                      AND previous_version.content_hash = current_notice.content_hash
                )
                """, Timestamp.from(capturedAt), existing.getId());
    }

    private void updateCurrent(Long id, BidNotice notice, Instant lastSeenAt) {
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bid_notice SET
                        notice_number = ?, title = ?, ordering_organization = ?, published_at = ?,
                        submission_deadline_at = ?, bid_opening_at = ?, contract_method = ?, bid_method = ?,
                        notice_status = ?, notice_status_code = ?, detail_url = ?, relevant = ?,
                        analysis_status = ?, analysis_result = ?, content_hash = ?, last_seen_at = ?
                    WHERE id = ?
                    """);
            setNullableString(statement, 1, notice.getNoticeNumber());
            statement.setString(2, notice.getTitle());
            setNullableString(statement, 3, notice.getOrderingOrganization());
            setLocalDateTime(statement, 4, notice.getPublishedAt());
            setLocalDateTime(statement, 5, notice.getSubmissionDeadlineAt());
            setLocalDateTime(statement, 6, notice.getBidOpeningAt());
            setNullableString(statement, 7, notice.getContractMethod());
            setNullableString(statement, 8, notice.getBidMethod());
            setNullableString(statement, 9, notice.getNoticeStatus());
            setNullableString(statement, 10, notice.getNoticeStatusCode());
            setNullableString(statement, 11, notice.getDetailUrl());
            statement.setBoolean(12, notice.isRelevant());
            setNullableString(statement, 13, notice.getAnalysisStatus());
            setNullableString(statement, 14, notice.getAnalysisResult());
            statement.setString(15, notice.getContentHash());
            statement.setTimestamp(16, Timestamp.from(lastSeenAt));
            statement.setLong(17, id);
            return statement;
        });
        if (updated != 1) {
            throw new IllegalStateException("Bid notice update did not affect exactly one row: " + id);
        }
    }

    private void setCurrentValues(
            PreparedStatement statement,
            BidNotice notice,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) throws SQLException {
        statement.setString(1, notice.getSourceCode());
        statement.setString(2, notice.getSourceNoticeId());
        statement.setString(3, notice.getRevisionKey());
        setNullableString(statement, 4, notice.getNoticeNumber());
        statement.setString(5, notice.getTitle());
        setNullableString(statement, 6, notice.getOrderingOrganization());
        setLocalDateTime(statement, 7, notice.getPublishedAt());
        setLocalDateTime(statement, 8, notice.getSubmissionDeadlineAt());
        setLocalDateTime(statement, 9, notice.getBidOpeningAt());
        setNullableString(statement, 10, notice.getContractMethod());
        setNullableString(statement, 11, notice.getBidMethod());
        setNullableString(statement, 12, notice.getNoticeStatus());
        setNullableString(statement, 13, notice.getNoticeStatusCode());
        setNullableString(statement, 14, notice.getDetailUrl());
        statement.setBoolean(15, notice.isRelevant());
        setNullableString(statement, 16, notice.getAnalysisStatus());
        setNullableString(statement, 17, notice.getAnalysisResult());
        statement.setString(18, notice.getContentHash());
        statement.setTimestamp(19, Timestamp.from(firstSeenAt));
        statement.setTimestamp(20, Timestamp.from(lastSeenAt));
    }

    private BidNotice normalize(BidNotice notice) {
        Objects.requireNonNull(notice, "Bid notice is required.");
        String hash = requireText(notice.getContentHash(), "contentHash");
        if (!CONTENT_HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("contentHash must be a 64-character hexadecimal SHA-256 value.");
        }
        Instant firstSeenAt = Objects.requireNonNull(notice.getFirstSeenAt(), "firstSeenAt is required.");
        Instant lastSeenAt = Objects.requireNonNull(notice.getLastSeenAt(), "lastSeenAt is required.");
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastSeenAt must not be before firstSeenAt.");
        }
        return notice.toBuilder()
                .sourceCode(requireText(notice.getSourceCode(), "sourceCode"))
                .sourceNoticeId(requireText(notice.getSourceNoticeId(), "sourceNoticeId"))
                .revisionKey(normalizeRevision(notice.getRevisionKey()))
                .title(requireText(notice.getTitle(), "title"))
                .contentHash(hash.toLowerCase(Locale.ROOT))
                .build();
    }

    private String normalizeRevision(String revision) {
        return revision == null ? "" : revision.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private BidNotice mapNotice(ResultSet rs, int rowNumber) throws SQLException {
        return BidNotice.builder()
                .id(rs.getLong("id"))
                .sourceCode(rs.getString("source_code"))
                .sourceNoticeId(rs.getString("source_notice_id"))
                .revisionKey(rs.getString("revision_key"))
                .noticeNumber(rs.getString("notice_number"))
                .title(rs.getString("title"))
                .orderingOrganization(rs.getString("ordering_organization"))
                .publishedAt(localDateTime(rs, "published_at"))
                .submissionDeadlineAt(localDateTime(rs, "submission_deadline_at"))
                .bidOpeningAt(localDateTime(rs, "bid_opening_at"))
                .contractMethod(rs.getString("contract_method"))
                .bidMethod(rs.getString("bid_method"))
                .noticeStatus(rs.getString("notice_status"))
                .noticeStatusCode(rs.getString("notice_status_code"))
                .detailUrl(rs.getString("detail_url"))
                .relevant(rs.getBoolean("relevant"))
                .analysisStatus(rs.getString("analysis_status"))
                .analysisResult(rs.getString("analysis_result"))
                .contentHash(rs.getString("content_hash"))
                .firstSeenAt(rs.getTimestamp("first_seen_at").toInstant())
                .lastSeenAt(rs.getTimestamp("last_seen_at").toInstant())
                .build();
    }

    private BidNoticeVersion mapVersion(ResultSet rs, int rowNumber) throws SQLException {
        return BidNoticeVersion.builder()
                .id(rs.getLong("id"))
                .bidNoticeId(rs.getLong("bid_notice_id"))
                .noticeNumber(rs.getString("notice_number"))
                .title(rs.getString("title"))
                .orderingOrganization(rs.getString("ordering_organization"))
                .publishedAt(localDateTime(rs, "published_at"))
                .submissionDeadlineAt(localDateTime(rs, "submission_deadline_at"))
                .bidOpeningAt(localDateTime(rs, "bid_opening_at"))
                .contractMethod(rs.getString("contract_method"))
                .bidMethod(rs.getString("bid_method"))
                .noticeStatus(rs.getString("notice_status"))
                .noticeStatusCode(rs.getString("notice_status_code"))
                .detailUrl(rs.getString("detail_url"))
                .relevant(rs.getBoolean("relevant"))
                .analysisStatus(rs.getString("analysis_status"))
                .analysisResult(rs.getString("analysis_result"))
                .contentHash(rs.getString("content_hash"))
                .firstSeenAt(rs.getTimestamp("first_seen_at").toInstant())
                .lastSeenAt(rs.getTimestamp("last_seen_at").toInstant())
                .capturedAt(rs.getTimestamp("captured_at").toInstant())
                .build();
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private void setLocalDateTime(PreparedStatement statement, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
