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
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** submission_case를 기존 primary H2 JdbcTemplate에 저장한다. */
@Repository
public class JdbcSubmissionCaseRepository implements SubmissionCaseRepository {

    private static final String COLUMNS = """
            id, project_id, project_public_id, project_code, internal_biz_no,
            project_name, bid_notice_no, status, created_at, updated_at,
            organization_name, performance_project_id, performance_link_initialized, deadline
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
                if (submissionCase.getProjectId() == null) {
                    statement.setNull(1, Types.BIGINT);
                } else {
                    statement.setLong(1, submissionCase.getProjectId());
                }
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
            if (submissionCase.getProjectId() != null) {
                return findByProjectId(submissionCase.getProjectId()).orElseThrow(() -> exception);
            }
            throw exception;
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
        if (projectId == null) {
            return Optional.empty();
        }
        return find("SELECT " + COLUMNS + " FROM submission_case WHERE project_id = ?", projectId);
    }

    public List<SubmissionCase> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM submission_case ORDER BY updated_at DESC, id DESC", this::map);
    }
    public SubmissionCase lock(Long id) {
        return find("SELECT " + COLUMNS + " FROM submission_case WHERE id=? FOR UPDATE", id)
                .orElseThrow(() -> new com.comhu.bidmonitor.submission.service.SubmissionNotFoundException("프로젝트를 찾을 수 없습니다."));
    }
    public void delete(Long id) {
        // Existing ON DELETE CASCADE FKs remove only this case's requirements and selections.
        jdbcTemplate.update("DELETE FROM submission_case WHERE id=?", id);
    }
    public void update(SubmissionCase value) {
        jdbcTemplate.update("UPDATE submission_case SET project_name=?,organization_name=?,performance_project_id=?,performance_link_initialized=?,updated_at=?,deadline=? WHERE id=?",
                value.getProjectName(), value.getOrganizationName(), value.getPerformanceProjectId(), value.isPerformanceLinkInitialized(),
                Timestamp.from(value.getUpdatedAt()), value.getDeadline(), value.getId());
    }
    public boolean performanceProjectExists(String id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_project WHERE id=?", Long.class, id) > 0;
    }
    public long performanceTotal(String id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_entry WHERE project_id=?", Long.class, id);
    }
    public long personnelTotal(Long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submission_person_document d JOIN submission_person p ON p.id=d.person_id WHERE p.submission_case_id=? AND d.needed=TRUE", Long.class, id);
    }
    public long personnelPrepared(Long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submission_person_document d JOIN submission_person p ON p.id=d.person_id WHERE p.submission_case_id=? AND d.needed=TRUE AND d.filename IS NOT NULL", Long.class, id);
    }
    public long performanceMissing(String id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_entry WHERE project_id=? AND selected_file_id IS NULL AND selected_drive_file_id IS NULL AND selected_uploaded_file_id IS NULL", Long.class, id);
    }
    private Optional<SubmissionCase> find(String sql, Object parameter) {
        List<SubmissionCase> rows = jdbcTemplate.query(sql, this::map, parameter);
        return rows.stream().findFirst();
    }

    private SubmissionCase map(ResultSet resultSet, int rowNumber) throws SQLException {
        return SubmissionCase.builder()
                .id(resultSet.getLong("id"))
                .projectId(resultSet.getObject("project_id", Long.class))
                .projectPublicId(toUuid(resultSet.getString("project_public_id")))
                .projectCode(resultSet.getString("project_code"))
                .internalBizNo(resultSet.getString("internal_biz_no"))
                .projectName(resultSet.getString("project_name"))
                .deadline(resultSet.getObject("deadline", java.time.LocalDate.class))
                .organizationName(resultSet.getString("organization_name"))
                .performanceProjectId(resultSet.getString("performance_project_id"))
                .performanceLinkInitialized(resultSet.getBoolean("performance_link_initialized"))
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
