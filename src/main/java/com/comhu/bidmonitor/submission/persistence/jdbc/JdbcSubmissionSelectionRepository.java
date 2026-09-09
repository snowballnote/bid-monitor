package com.comhu.bidmonitor.submission.persistence.jdbc;

import com.comhu.bidmonitor.submission.domain.SubmissionDocumentSelection;
import com.comhu.bidmonitor.submission.persistence.SubmissionSelectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 선택 목록 전체 교체를 하나의 H2 트랜잭션으로 처리한다. */
@Repository
public class JdbcSubmissionSelectionRepository implements SubmissionSelectionRepository {

    private static final String COLUMNS = """
            id, submission_case_id, requirement_id, file_id, file_public_id,
            original_filename, file_ext, file_modified_at, file_updated_at, selected_at, uploaded_file_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionSelectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public List<SubmissionDocumentSelection> replaceForCase(
            Long submissionCaseId,
            List<SubmissionDocumentSelection> selections
    ) {
        jdbcTemplate.update(
                "DELETE FROM submission_document_selection WHERE submission_case_id = ?",
                submissionCaseId
        );
        for (SubmissionDocumentSelection selection : selections) {
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO submission_document_selection (
                            submission_case_id, requirement_id, file_id, file_public_id,
                            original_filename, file_ext, file_modified_at, file_updated_at, selected_at, uploaded_file_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                statement.setLong(1, submissionCaseId);
                statement.setLong(2, selection.getRequirementId());
                statement.setObject(3, selection.getFileId(), java.sql.Types.BIGINT);
                statement.setString(4, selection.getFilePublicId() == null
                        ? null : selection.getFilePublicId().toString());
                statement.setString(5, selection.getOriginalFilename());
                statement.setString(6, selection.getFileExt());
                setTimestamp(statement, 7, selection.getFileModifiedAt());
                setTimestamp(statement, 8, selection.getFileUpdatedAt());
                statement.setTimestamp(9, Timestamp.from(selection.getSelectedAt()));
                statement.setString(10,selection.getUploadedFileId());
                return statement;
            });
        }
        return findBySubmissionCaseId(submissionCaseId);
    }

    @Override
    public List<SubmissionDocumentSelection> findBySubmissionCaseId(Long submissionCaseId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM submission_document_selection "
                        + "WHERE submission_case_id = ? ORDER BY requirement_id, id",
                this::map,
                submissionCaseId
        );
    }

    private SubmissionDocumentSelection map(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp modifiedAt = resultSet.getTimestamp("file_modified_at");
        Timestamp updatedAt = resultSet.getTimestamp("file_updated_at");
        return SubmissionDocumentSelection.builder()
                .id(resultSet.getLong("id"))
                .submissionCaseId(resultSet.getLong("submission_case_id"))
                .requirementId(resultSet.getLong("requirement_id"))
                .fileId(resultSet.getObject("file_id",Long.class))
                .uploadedFileId(resultSet.getString("uploaded_file_id"))
                .filePublicId(toUuid(resultSet.getString("file_public_id")))
                .originalFilename(resultSet.getString("original_filename"))
                .fileExt(resultSet.getString("file_ext"))
                .fileModifiedAt(modifiedAt == null ? null : modifiedAt.toInstant())
                .fileUpdatedAt(updatedAt == null ? null : updatedAt.toInstant())
                .selectedAt(resultSet.getTimestamp("selected_at").toInstant())
                .build();
    }

    private void setTimestamp(java.sql.PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private UUID toUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
