package com.comhu.bidmonitor.submission.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Local catalog only; project requirements remain independent snapshots. */
@Service
public class SubmissionDocumentMasterService {
    private final JdbcTemplate jdbc;
    public SubmissionDocumentMasterService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public enum Category { COMPANY_COMMON, PERSONNEL, PERFORMANCE, OTHER }
    public record Input(String name, Category category) { }
    public record Item(String id, String name, Category category, String requirementCategory, String sourceReference, Long currentFileId, String currentFilename, String uploadedFileId, Long sizeBytes, java.time.Instant uploadedAt) { }
    public List<Item> list() {
        return jdbc.query("SELECT m.*,c.file_id,c.original_filename,c.uploaded_file_id,u.size_bytes,u.created_at AS uploaded_at FROM submission_document_master m LEFT JOIN submission_common_document c ON c.document_type=m.source_reference AND c.active=TRUE LEFT JOIN submission_uploaded_file u ON u.id=c.uploaded_file_id WHERE m.active=TRUE ORDER BY m.sort_order,m.id",
                (rs,n) -> new Item(rs.getString("id"),rs.getString("name"),Category.valueOf(rs.getString("category")),
                        rs.getString("requirement_category"),rs.getString("source_reference"),rs.getObject("file_id",Long.class),rs.getString("original_filename"),rs.getString("uploaded_file_id"),rs.getObject("size_bytes",Long.class),rs.getTimestamp("uploaded_at")==null?null:rs.getTimestamp("uploaded_at").toInstant()));
    }
    private String name(Input input) {
        if (input == null || input.category() == null || input.name() == null || input.name().isBlank() || input.name().trim().length() > 200)
            throw new IllegalArgumentException("서류명(200자 이하)과 카테고리를 입력하세요.");
        return input.name().trim();
    }
    private String requirementCategory(Category category) { return category == Category.COMPANY_COMMON ? "COMPANY_GENERAL" : category.name(); }
    @Transactional
    public Item create(Input input) {
        String name = name(input), id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO submission_document_master(id,name,category,requirement_category,source_reference) VALUES (?,?,?,?,?)",
                id,name,input.category().name(),requirementCategory(input.category()),"MASTER_"+id);
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public Item update(String id, Input input) {
        String name = name(input);
        // Preserve legacy requirement categories when only the name changes.
        int count = jdbc.update("UPDATE submission_document_master SET name=?,requirement_category=CASE WHEN category=? THEN requirement_category ELSE ? END,category=? WHERE id=? AND active=TRUE",
                name,input.category().name(),requirementCategory(input.category()),input.category().name(),id);
        if (count == 0) throw new SubmissionNotFoundException("서류 항목을 찾을 수 없습니다.");
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public void delete(String id) {
        // Tombstones prevent deleted defaults from being seeded again on startup.
        if (jdbc.update("UPDATE submission_document_master SET active=FALSE WHERE id=? AND active=TRUE",id) == 0)
            throw new SubmissionNotFoundException("서류 항목을 찾을 수 없습니다.");
    }
}
