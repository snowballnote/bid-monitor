package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Only primary H2 keeps the private Drive locator; API clients receive the random ID. */
@Repository
public class PerformanceDriveFileRepository {
    public record Reference(String id, String origin, String company, String path, String filename, String ext,
                            long size, Instant modified) { }
    private final JdbcTemplate jdbc;
    public PerformanceDriveFileRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Reference register(String origin, String company, FmsDrivePort.Item item) {
        String id = UUID.randomUUID().toString();
        int dot = item.name().lastIndexOf('.');
        String ext = dot < 0 ? "" : item.name().substring(dot + 1);
        try {
            jdbc.update("""
                    INSERT INTO performance_drive_file(id, origin, company, drive_path, filename, file_ext, file_size, modified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, origin, company, item.path(), item.name(), ext, item.size(),
                    item.lastModified() == null ? null : Timestamp.from(item.lastModified()));
        } catch (DuplicateKeyException existing) {
            id = jdbc.queryForObject("SELECT id FROM performance_drive_file WHERE origin = ? AND company = ? AND drive_path = ?",
                    String.class, origin, company, item.path());
            jdbc.update("UPDATE performance_drive_file SET filename = ?, file_ext = ?, file_size = ?, modified_at = ? WHERE id = ?",
                    item.name(), ext, item.size(), item.lastModified() == null ? null : Timestamp.from(item.lastModified()), id);
        }
        return find(id);
    }

    public Reference find(String id) {
        return jdbc.query("SELECT * FROM performance_drive_file WHERE id = ?", (rs, row) -> {
            Timestamp time = rs.getTimestamp("modified_at");
            return new Reference(rs.getString("id"), rs.getString("origin"), rs.getString("company"),
                    rs.getString("drive_path"), rs.getString("filename"), rs.getString("file_ext"),
                    rs.getLong("file_size"), time == null ? null : time.toInstant());
        }, id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("조회된 Drive 후보를 선택하세요."));
    }
}