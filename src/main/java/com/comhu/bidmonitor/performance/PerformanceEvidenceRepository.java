package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Repository
public class PerformanceEvidenceRepository {
    private final JdbcTemplate jdbc;

    public PerformanceEvidenceRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Evidence> findByEntryId(String entryId) {
        return jdbc.query("""
                SELECT * FROM performance_evidence
                WHERE entry_id = ?
                ORDER BY created_at, id
                """, this::map, entryId);
    }

    public Evidence find(String entryId, String id) {
        return jdbc.query("""
                SELECT * FROM performance_evidence
                WHERE entry_id = ? AND id = ?
                """, this::map, entryId, id).stream().findFirst()
                .orElseThrow(PerformanceNotFoundException::new);
    }

    public Evidence add(String entryId, EvidenceInput input, String filename, String fileExt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO performance_evidence(
                    id, entry_id, evidence_type,
                    selected_file_id, selected_drive_file_id, selected_uploaded_file_id,
                    filename, file_ext, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, entryId, name(input.evidenceType()), input.selectedFileId(),
                input.selectedDriveFileId(), input.selectedUploadedFileId(), filename, fileExt);
        return find(entryId, id);
    }

    public Evidence update(String entryId, String id, EvidenceInput input, String filename, String fileExt) {
        int count = jdbc.update("""
                UPDATE performance_evidence
                SET evidence_type = ?, selected_file_id = ?, selected_drive_file_id = ?,
                    selected_uploaded_file_id = ?, filename = ?, file_ext = ?, updated_at = CURRENT_TIMESTAMP
                WHERE entry_id = ? AND id = ?
                """, name(input.evidenceType()), input.selectedFileId(), input.selectedDriveFileId(),
                input.selectedUploadedFileId(), filename, fileExt, entryId, id);
        if (count == 0) throw new PerformanceNotFoundException();
        return find(entryId, id);
    }

    public void delete(String entryId, String id) {
        if (jdbc.update("DELETE FROM performance_evidence WHERE entry_id = ? AND id = ?", entryId, id) == 0) {
            throw new PerformanceNotFoundException();
        }
    }

    private Evidence map(ResultSet rs, int row) throws SQLException {
        return new Evidence(rs.getString("id"), rs.getString("entry_id"),
                EvidenceType.valueOf(rs.getString("evidence_type")),
                rs.getObject("selected_file_id", Long.class), rs.getString("selected_drive_file_id"),
                rs.getString("selected_uploaded_file_id"), rs.getString("filename"), rs.getString("file_ext"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
