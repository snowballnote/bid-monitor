package com.comhu.bidmonitor.submission.persistence.jdbc;

import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.domain.RequirementSourceType;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.persistence.SubmissionRequirementRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 제출 요구서류를 primary H2에 저장한다. */
@Repository
public class JdbcSubmissionRequirementRepository implements SubmissionRequirementRepository {

    private static final String COLUMNS = """
            id, submission_case_id, category, document_name, required,
            evidence_text, source_type, source_reference, created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionRequirementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void delete(Long caseId, Long requirementId) {
        jdbcTemplate.update("DELETE FROM submission_document_requirement WHERE submission_case_id=? AND id=?", caseId, requirementId);
    }

    @Override
    public List<SubmissionDocumentRequirement> saveAll(List<SubmissionDocumentRequirement> requirements) {
        List<SubmissionDocumentRequirement> saved = new ArrayList<>();
        for (SubmissionDocumentRequirement requirement : requirements) {
            saved.add(save(requirement));
        }
        return List.copyOf(saved);
    }

    private SubmissionDocumentRequirement save(SubmissionDocumentRequirement requirement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO submission_document_requirement (
                                submission_case_id, category, document_name, required,
                                evidence_text, source_type, source_reference, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, requirement.getSubmissionCaseId());
            statement.setString(2, requirement.getCategory().name());
            statement.setString(3, requirement.getDocumentName());
            statement.setBoolean(4, requirement.isRequired());
            statement.setString(5, requirement.getEvidenceText());
            statement.setString(6, requirement.getSourceType().name());
            statement.setString(7, requirement.getSourceReference());
            statement.setTimestamp(8, Timestamp.from(requirement.getCreatedAt()));
            return statement;
        }, keyHolder);
        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("저장된 제출 요구서류 ID를 가져올 수 없습니다.");
        }
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<SubmissionDocumentRequirement> findById(Long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM submission_document_requirement WHERE id = ?",
                this::map,
                id
        ).stream().findFirst();
    }

    @Override
    public List<SubmissionDocumentRequirement> findBySubmissionCaseId(Long submissionCaseId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM submission_document_requirement "
                        + "WHERE submission_case_id = ? ORDER BY id",
                this::map,
                submissionCaseId
        );
    }

    private SubmissionDocumentRequirement map(ResultSet resultSet, int rowNumber) throws SQLException {
        return SubmissionDocumentRequirement.builder()
                .id(resultSet.getLong("id"))
                .submissionCaseId(resultSet.getLong("submission_case_id"))
                .category(RequirementCategory.valueOf(resultSet.getString("category")))
                .documentName(resultSet.getString("document_name"))
                .required(resultSet.getBoolean("required"))
                .evidenceText(resultSet.getString("evidence_text"))
                .sourceType(RequirementSourceType.valueOf(resultSet.getString("source_type")))
                .sourceReference(resultSet.getString("source_reference"))
                .createdAt(resultSet.getTimestamp("created_at").toInstant())
                .build();
    }
}
