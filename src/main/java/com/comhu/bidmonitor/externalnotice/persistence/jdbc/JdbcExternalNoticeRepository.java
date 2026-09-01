package com.comhu.bidmonitor.externalnotice.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Spring JDBC로 공지 본문과 1:N 첨부파일을 하나의 트랜잭션에서 저장한다. */
@Repository
public class JdbcExternalNoticeRepository implements ExternalNoticeRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final String NOTICE_COLUMNS = """
            id, source_code, external_id, source_notice_id, title, published_date,
            detail_url, body, pia_related, matched_keywords, classification_reason,
            fingerprint, first_seen_at, last_seen_at
            """;
    private static final String INSERT_NOTICE_SQL = """
            INSERT INTO external_notice (
                source_code, external_id, source_notice_id, title, published_date,
                detail_url, body, pia_related, matched_keywords, classification_reason,
                fingerprint, first_seen_at, last_seen_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ATTACHMENT_SQL = """
            INSERT INTO external_notice_attachment (
                external_notice_id, attachment_order, file_name, file_url
            ) VALUES (?, ?, ?, ?)
            """;
    private static final String UPDATE_CONTENT_SQL = """
            UPDATE external_notice SET
                title = ?, published_date = ?, detail_url = ?, body = ?,
                pia_related = ?, matched_keywords = ?, classification_reason = ?,
                fingerprint = ?, last_seen_at = ?
            WHERE id = ? AND external_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcExternalNoticeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 프로젝트의 Jackson 2 의존성을 저장 형식이 단순한 문자열 배열 직렬화에만 한정해 사용한다.
        this.objectMapper = new ObjectMapper();
    }

    /** 이번 단계의 save는 신규 저장만 담당하며 같은 externalId는 DB 유일성 제약으로 거부한다. */
    @Override
    @Transactional
    public ExternalNotice save(ExternalNotice notice) {
        Objects.requireNonNull(notice, "저장할 외부공지는 null일 수 없습니다.");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    INSERT_NOTICE_SQL,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, notice.getSourceCode());
            statement.setString(2, notice.getExternalId());
            statement.setString(3, notice.getSourceNoticeId());
            statement.setString(4, notice.getTitle());
            setDate(statement, 5, notice.getPublishedDate());
            statement.setString(6, notice.getDetailUrl());
            statement.setString(7, notice.getBody());
            statement.setBoolean(8, notice.isPiaRelated());
            statement.setString(9, serializeKeywords(notice.getMatchedKeywords()));
            statement.setString(10, notice.getClassificationReason());
            statement.setString(11, notice.getFingerprint());
            setTimestamp(statement, 12, notice.getFirstSeenAt());
            setTimestamp(statement, 13, notice.getLastSeenAt());
            return statement;
        }, keyHolder);

        Long noticeId = keyHolder.getKeyAs(Long.class);
        if (noticeId == null) {
            throw new IllegalStateException("저장된 외부공지의 ID를 가져올 수 없습니다.");
        }
        saveAttachments(noticeId, notice.getAttachments());
        return findByExternalId(notice.getExternalId())
                .orElseThrow(() -> new IllegalStateException("저장한 외부공지를 다시 조회할 수 없습니다."));
    }

    @Override
    public Optional<ExternalNotice> findByExternalId(String externalId) {
        List<NoticeRow> rows = jdbcTemplate.query(
                "SELECT " + NOTICE_COLUMNS + " FROM external_notice WHERE external_id = ?",
                this::mapNoticeRow,
                externalId
        );
        return rows.stream().findFirst().map(this::restoreNotice);
    }

    @Override
    public Optional<ExternalNotice> findById(Long id) {
        List<NoticeRow> rows = jdbcTemplate.query(
                "SELECT " + NOTICE_COLUMNS + " FROM external_notice WHERE id = ?",
                this::mapNoticeRow,
                id
        );
        return rows.stream().findFirst().map(this::restoreNotice);
    }

    @Override
    public List<ExternalNotice> findAll() {
        return jdbcTemplate.query(
                        "SELECT " + NOTICE_COLUMNS + " FROM external_notice ORDER BY id",
                        this::mapNoticeRow
                ).stream()
                .map(this::restoreNotice)
                .toList();
    }

    @Override
    public List<ExternalNotice> findAllLatestFirst() {
        return findAllWithSql("""
                SELECT %s FROM external_notice
                ORDER BY published_date DESC NULLS LAST, id DESC
                """.formatted(NOTICE_COLUMNS));
    }

    @Override
    public List<ExternalNotice> findAllByPiaRelatedLatestFirst(boolean piaRelated) {
        return jdbcTemplate.query(
                        """
                                SELECT %s FROM external_notice
                                WHERE pia_related = ?
                                ORDER BY published_date DESC NULLS LAST, id DESC
                                """.formatted(NOTICE_COLUMNS),
                        this::mapNoticeRow,
                        piaRelated
                ).stream()
                .map(this::restoreNotice)
                .toList();
    }

    @Override
    public boolean existsByExternalId(String externalId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM external_notice WHERE external_id = ?",
                Long.class,
                externalId
        );
        return count != null && count > 0;
    }

    /** 내용이 같을 때는 기존 데이터를 건드리지 않고 마지막 확인시각만 갱신한다. */
    @Override
    public void updateLastSeenAt(Long noticeId, Instant lastSeenAt) {
        int updatedRows = jdbcTemplate.update(
                "UPDATE external_notice SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(lastSeenAt),
                noticeId
        );
        requireSingleUpdatedRow(updatedRows, noticeId);
    }

    /**
     * 변경된 콘텐츠와 분류 결과를 교체하되 DB ID, 외부 식별값과 최초 발견시각은 유지한다.
     * 첨부파일 삭제와 재등록도 같은 트랜잭션에 포함해 공지와 첨부 상태가 어긋나지 않게 한다.
     */
    @Override
    @Transactional
    public ExternalNotice updateContent(Long noticeId, ExternalNotice notice) {
        Objects.requireNonNull(notice, "갱신할 외부공지는 null일 수 없습니다.");

        int updatedRows = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(UPDATE_CONTENT_SQL);
            statement.setString(1, notice.getTitle());
            setDate(statement, 2, notice.getPublishedDate());
            statement.setString(3, notice.getDetailUrl());
            statement.setString(4, notice.getBody());
            statement.setBoolean(5, notice.isPiaRelated());
            statement.setString(6, serializeKeywords(notice.getMatchedKeywords()));
            statement.setString(7, notice.getClassificationReason());
            statement.setString(8, notice.getFingerprint());
            setTimestamp(statement, 9, notice.getLastSeenAt());
            statement.setLong(10, noticeId);
            statement.setString(11, notice.getExternalId());
            return statement;
        });
        requireSingleUpdatedRow(updatedRows, noticeId);

        jdbcTemplate.update(
                "DELETE FROM external_notice_attachment WHERE external_notice_id = ?",
                noticeId
        );
        saveAttachments(noticeId, notice.getAttachments());
        return findByExternalId(notice.getExternalId())
                .orElseThrow(() -> new IllegalStateException("갱신한 외부공지를 다시 조회할 수 없습니다."));
    }

    private void saveAttachments(Long noticeId, List<ExternalNoticeAttachment> attachments) {
        if (attachments == null) {
            return;
        }
        for (int index = 0; index < attachments.size(); index++) {
            ExternalNoticeAttachment attachment = attachments.get(index);
            jdbcTemplate.update(
                    INSERT_ATTACHMENT_SQL,
                    noticeId,
                    index,
                    attachment.getFileName(),
                    attachment.getFileUrl()
            );
        }
    }

    private List<ExternalNotice> findAllWithSql(String sql) {
        return jdbcTemplate.query(sql, this::mapNoticeRow).stream()
                .map(this::restoreNotice)
                .toList();
    }

    private NoticeRow mapNoticeRow(ResultSet resultSet, int rowNumber) throws SQLException {
        Date publishedDate = resultSet.getDate("published_date");
        return new NoticeRow(
                resultSet.getLong("id"),
                resultSet.getString("source_code"),
                resultSet.getString("external_id"),
                resultSet.getString("source_notice_id"),
                resultSet.getString("title"),
                publishedDate == null ? null : publishedDate.toLocalDate(),
                resultSet.getString("detail_url"),
                resultSet.getString("body"),
                resultSet.getBoolean("pia_related"),
                deserializeKeywords(resultSet.getString("matched_keywords")),
                resultSet.getString("classification_reason"),
                resultSet.getString("fingerprint"),
                resultSet.getTimestamp("first_seen_at").toInstant(),
                resultSet.getTimestamp("last_seen_at").toInstant()
        );
    }

    private ExternalNotice restoreNotice(NoticeRow row) {
        return ExternalNotice.builder()
                .id(row.id())
                .sourceCode(row.sourceCode())
                .externalId(row.externalId())
                .sourceNoticeId(row.sourceNoticeId())
                .title(row.title())
                .publishedDate(row.publishedDate())
                .detailUrl(row.detailUrl())
                .body(row.body())
                .piaRelated(row.piaRelated())
                .matchedKeywords(row.matchedKeywords())
                .classificationReason(row.classificationReason())
                .fingerprint(row.fingerprint())
                .firstSeenAt(row.firstSeenAt())
                .lastSeenAt(row.lastSeenAt())
                .attachments(findAttachments(row.id()))
                .build();
    }

    private List<ExternalNoticeAttachment> findAttachments(Long noticeId) {
        return jdbcTemplate.query(
                """
                        SELECT id, external_notice_id, file_name, file_url
                        FROM external_notice_attachment
                        WHERE external_notice_id = ?
                        ORDER BY attachment_order
                        """,
                (resultSet, rowNumber) -> ExternalNoticeAttachment.builder()
                        .id(resultSet.getLong("id"))
                        .externalNoticeId(resultSet.getLong("external_notice_id"))
                        .fileName(resultSet.getString("file_name"))
                        .fileUrl(resultSet.getString("file_url"))
                        .build(),
                noticeId
        );
    }

    private String serializeKeywords(List<String> matchedKeywords) {
        try {
            return objectMapper.writeValueAsString(matchedKeywords == null ? List.of() : matchedKeywords);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PIA 매칭 키워드를 JSON으로 변환할 수 없습니다.", e);
        }
    }

    private List<String> deserializeKeywords(String value) throws SQLException {
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("저장된 PIA 매칭 키워드 JSON을 읽을 수 없습니다.", e);
        }
    }

    private void setDate(PreparedStatement statement, int index, LocalDate value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DATE);
        } else {
            statement.setDate(index, Date.valueOf(value));
        }
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private void requireSingleUpdatedRow(int updatedRows, Long noticeId) {
        if (updatedRows != 1) {
            throw new IllegalStateException("갱신할 외부공지 한 건을 찾을 수 없습니다: " + noticeId);
        }
    }

    private record NoticeRow(
            Long id,
            String sourceCode,
            String externalId,
            String sourceNoticeId,
            String title,
            LocalDate publishedDate,
            String detailUrl,
            String body,
            boolean piaRelated,
            List<String> matchedKeywords,
            String classificationReason,
            String fingerprint,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }
}
