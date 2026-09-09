package com.comhu.bidmonitor.submission.persistence.jdbc;

import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.domain.CommonSubmissionDocument;
import com.comhu.bidmonitor.submission.domain.DocumentRefreshPolicy;
import com.comhu.bidmonitor.submission.persistence.CommonSubmissionDocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 공통서류 reference와 날짜만 primary H2 JdbcTemplate으로 읽고 쓴다. */
@Repository
public class JdbcCommonSubmissionDocumentRepository implements CommonSubmissionDocumentRepository {

    private static final String COLUMNS = """
            document_type, display_name, file_id, file_public_id, original_filename, file_ext,
            issued_at, expires_at, refresh_policy, refresh_interval_months, active,
            created_at, updated_at, uploaded_file_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommonSubmissionDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<CommonSubmissionDocument> findAllActive() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM submission_common_document WHERE active = TRUE AND document_type NOT LIKE 'MASTER_%' ORDER BY document_type",
                this::map
        );
    }

    @Override
    public Optional<CommonSubmissionDocument> findByDocumentType(CommonDocumentType documentType) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM submission_common_document WHERE document_type = ?",
                this::map,
                documentType.name()
        ).stream().findFirst();
    }

    @Override
    public CommonSubmissionDocument updateCurrentReference(CommonSubmissionDocument document) {
        int updated = jdbcTemplate.update(
                """
                        UPDATE submission_common_document
                        SET uploaded_file_id = NULL, file_id = ?, file_public_id = ?, original_filename = ?, file_ext = ?,
                            issued_at = ?, expires_at = ?, updated_at = ?
                        WHERE document_type = ? AND active = TRUE
                        """,
                document.getFileId(),
                document.getFilePublicId().toString(),
                document.getOriginalFilename(),
                document.getFileExt(),
                toSqlDate(document.getIssuedAt()),
                toSqlDate(document.getExpiresAt()),
                Timestamp.from(document.getUpdatedAt()),
                document.getDocumentType().name()
        );
        if (updated != 1) {
            throw new IllegalArgumentException("지원하지 않거나 비활성화된 공통서류 유형입니다.");
        }
        return findByDocumentType(document.getDocumentType()).orElseThrow();
    }

    private CommonSubmissionDocument map(ResultSet resultSet, int rowNumber) throws SQLException {
        String publicId = resultSet.getString("file_public_id");
        Date issuedAt = resultSet.getDate("issued_at");
        Date expiresAt = resultSet.getDate("expires_at");
        return CommonSubmissionDocument.builder()
                .documentType(CommonDocumentType.valueOf(resultSet.getString("document_type")))
                .displayName(resultSet.getString("display_name"))
                .fileId(resultSet.getObject("file_id", Long.class))
                .uploadedFileId(resultSet.getString("uploaded_file_id"))
                .filePublicId(publicId == null ? null : UUID.fromString(publicId))
                .originalFilename(resultSet.getString("original_filename"))
                .fileExt(resultSet.getString("file_ext"))
                .issuedAt(issuedAt == null ? null : issuedAt.toLocalDate())
                .expiresAt(expiresAt == null ? null : expiresAt.toLocalDate())
                .refreshPolicy(DocumentRefreshPolicy.valueOf(resultSet.getString("refresh_policy")))
                .refreshIntervalMonths(resultSet.getObject("refresh_interval_months", Integer.class))
                .active(resultSet.getBoolean("active"))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .updatedAt(resultSet.getTimestamp("updated_at").toInstant())
                .build();
    }

    private Date toSqlDate(java.time.LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
