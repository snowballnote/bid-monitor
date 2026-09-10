package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Repository
public class PerformanceRepository {
    private final JdbcTemplate jdbc;
    public PerformanceRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Project create(ProjectInput input) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO performance_project(id, name, deadline) VALUES (?, ?, ?)",
                id, input.name().trim(), input.deadline());
        return project(id);
    }

    public Project updateProject(String id, ProjectInput input) {
        project(id);
        jdbc.update("UPDATE performance_project SET name = ?, deadline = ? WHERE id = ?",
                input.name().trim(), input.deadline(), id);
        return project(id);
    }

    public List<Project> projects() {
        return jdbc.query("""
                SELECT p.*, (SELECT COUNT(*) FROM performance_entry e WHERE e.project_id = p.id) AS total,
                (SELECT COUNT(*) FROM performance_entry e WHERE e.project_id = p.id AND e.selected_file_id IS NULL AND e.selected_drive_file_id IS NULL AND e.selected_uploaded_file_id IS NULL) AS missing
                FROM performance_project p ORDER BY deadline, name, id
                """, (rs, row) -> new Project(rs.getString("id"), rs.getString("name"),
                rs.getObject("deadline", LocalDate.class),
                ChronoUnit.DAYS.between(LocalDate.now(), rs.getObject("deadline", LocalDate.class)),
                rs.getInt("total") == 0 ? "DRAFT" : rs.getInt("missing") == 0 ? "READY" : "COLLECTING"));
    }

    public Project project(String id) {
        return projects().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new PerformanceNotFoundException());
    }

    public List<Entry> entries(String projectId) {
        project(projectId);
        return jdbc.query("SELECT * FROM performance_entry WHERE project_id = ? ORDER BY ppt_number, id",
                this::map, projectId);
    }

    public Entry entry(String projectId, String id) {
        return jdbc.query("SELECT * FROM performance_entry WHERE project_id = ? AND id = ?",
                this::map, projectId, id).stream().findFirst().orElseThrow(() -> new PerformanceNotFoundException());
    }

    public Entry insert(String projectId, EntryInput input) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO performance_entry(id, project_id, ppt_number, business_name, business_period,
                contract_amount, client, business_status, kitc_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, input.pptNumber(), input.businessName(), input.businessPeriod(),
                input.contractAmount(), input.client(), name(input.businessStatus()), input.kitcStatus().name());
        return entry(projectId, id);
    }

    public Entry update(String projectId, String id, EntryInput input, String filename, String ext) {
        int count = jdbc.update("""
                UPDATE performance_entry SET ppt_number = ?, business_name = ?, business_period = ?,
                contract_amount = ?, client = ?, business_status = ?, selected_file_id = ?,
                selected_filename = ?, selected_ext = ?, evidence_type = ?, kitc_status = ?,
                requested_at = ?, replied_at = ?, selected_drive_file_id = ?, selected_uploaded_file_id = ? WHERE project_id = ? AND id = ?
                """, input.pptNumber(), input.businessName(), input.businessPeriod(), input.contractAmount(),
                input.client(), name(input.businessStatus()), input.selectedFileId(), filename, ext,
                name(input.evidenceType()), input.kitcStatus().name(), input.requestedAt(), input.repliedAt(), input.selectedDriveFileId(), input.selectedUploadedFileId(), projectId, id);
        if (count == 0) throw new PerformanceNotFoundException();
        return entry(projectId, id);
    }

    private Entry map(ResultSet rs, int row) throws SQLException {
        EntryInput input = new EntryInput(rs.getString("ppt_number"), rs.getString("business_name"),
                rs.getString("business_period"), rs.getString("contract_amount"), rs.getString("client"),
                enumValue(BusinessStatus.class, rs.getString("business_status")),
                rs.getObject("selected_file_id", Long.class),
                enumValue(EvidenceType.class, rs.getString("evidence_type")),
                KitcStatus.valueOf(rs.getString("kitc_status")),
                rs.getObject("requested_at", LocalDate.class), rs.getObject("replied_at", LocalDate.class), rs.getString("selected_drive_file_id"), rs.getString("selected_uploaded_file_id"));
        return new Entry(rs.getString("id"), rs.getString("project_id"), input, rs.getString("selected_filename"),
                rs.getString("selected_ext"), PerformanceService.resolveStatus(input));
    }

    private String name(Enum<?> value) { return value == null ? null : value.name(); }
    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}