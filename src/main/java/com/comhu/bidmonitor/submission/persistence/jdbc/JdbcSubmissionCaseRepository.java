package com.comhu.bidmonitor.submission.persistence.jdbc;

import com.comhu.bidmonitor.submission.domain.SubmissionCase;
import com.comhu.bidmonitor.submission.domain.SubmissionCaseStatus;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Optional;
import java.util.UUID;

/** submission_case를 기존 primary H2 JdbcTemplate에 저장한다. */
@Repository
public class JdbcSubmissionCaseRepository implements SubmissionCaseRepository {

    private static final String COLUMNS = """
            id, project_id, project_public_id, project_code, internal_biz_no,
            project_name, bid_notice_no, status, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionCaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SubmissionCase save(SubmissionCase submissionCase) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                                INSERT INTO submission_case (
                                    project_id, project_public_id, project_code, internal_biz_no,
                                    project_name, bid_notice_no, status, created_at, updated_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        Statement.RETURN_GENERATED_KEYS
                );
                statement.setLong(1, submissionCase.getProjectId());
                statement.setString(2, submissionCase.getProjectPublicId() == null
                        ? null : submissionCase.getProjectPublicId().toString());
                statement.setString(3, submissionCase.getProjectCode());
                statement.setString(4, submissionCase.getInternalBizNo());
                statement.setString(5, submissionCase.getProjectName());
                statement.setString(6, submissionCase.getBidNoticeNo());
                statement.setString(7, submissionCase.getStatus().name());
                statement.setTimestamp(8, Timestamp.from(submissionCase.getCreatedAt()));
                statement.setTimestamp(9, Timestamp.from(submissionCase.getUpdatedAt()));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            return findByProjectId(submissionCase.getProjectId()).orElseThrow(() -> exception);
        }

        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("저장된 제출서류 작업 ID를 가져올 수 없습니다.");
        }
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<SubmissionCase> findById(Long id) {
        return find("SELECT " + COLUMNS + " FROM submission_case WHERE id = ?", id);
    }

    @Override
    public Optional<SubmissionCase> findByProjectId(Long projectId) {
        return find("SELECT " + COLUMNS + " FROM submission_case WHERE project_id = ?", projectId);
    }

    private Optional<SubmissionCase> find(String sql, Object parameter) {
        List<SubmissionCase> rows = jdbcTemplate.query(sql, this::map, parameter);
        return rows.stream().findFirst();
    }

    private SubmissionCase map(ResultSet resultSet, int rowNumber) throws SQLException {
        return SubmissionCase.builder()
                .id(resultSet.getLong("id"))
                .projectId(resultSet.getLong("project_id"))
                .projectPublicId(toUuid(resultSet.getString("project_public_id")))
                .projectCode(resultSet.getString("project_code"))
                .internalBizNo(resultSet.getString("internal_biz_no"))
                .projectName(resultSet.getString("project_name"))
                .bidNoticeNo(resultSet.getString("bid_notice_no"))
                .status(SubmissionCaseStatus.valueOf(resultSet.getString("status")))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .updatedAt(resultSet.getTimestamp("updated_at").toInstant())
                .build();
    }

    private UUID toUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
