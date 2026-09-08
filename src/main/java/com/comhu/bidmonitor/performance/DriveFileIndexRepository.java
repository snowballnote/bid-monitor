package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Private metadata cache in Biz Assist H2 only. */
@Repository
public class DriveFileIndexRepository {
    public record State(String status, Instant lastAttemptAt, Instant lastSuccessAt,
                        long folderCount, long fileCount, String errorCode) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public DriveFileIndexRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public State state(String source, String company, String root) {
        return jdbc.query("SELECT * FROM drive_index_root_state WHERE source=? AND company=? AND root=?", (rs, n) ->
                new State(rs.getString("status"), instant(rs.getTimestamp("last_attempt_at")),
                        instant(rs.getTimestamp("last_success_at")), rs.getLong("folder_count"),
                        rs.getLong("file_count"), rs.getString("error_code")), source, company, root)
                .stream().findFirst().orElse(new State("NOT_BUILT", null, null, 0, 0, null));
    }
    public void started(String source, String company, String root) {
        transaction.executeWithoutResult(tx -> {
            if (jdbc.update("UPDATE drive_index_root_state SET status='REFRESHING', last_attempt_at=?, error_code=NULL WHERE source=? AND company=? AND root=?",
                    Timestamp.from(Instant.now()), source, company, root) == 0) {
                jdbc.update("INSERT INTO drive_index_root_state(source,company,root,status,last_attempt_at,folder_count,file_count) VALUES(?,?,?,'REFRESHING',?,0,0)",
                        source, company, root, Timestamp.from(Instant.now()));
            }
        });
    }
    public void failed(String source, String company, String root) {
        jdbc.update("UPDATE drive_index_root_state SET status='FAILED', error_code='REFRESH_FAILED' WHERE source=? AND company=? AND root=?", source, company, root);
    }
    public void replace(String source, String company, String root, List<FmsDrivePort.Item> files, int folders) {
        transaction.executeWithoutResult(tx -> {
            Instant now = Instant.now();
            jdbc.update("DELETE FROM drive_file_index WHERE source=? AND company=? AND root=?", source, company, root);
            for (var file : files) {
                int dot = file.name().lastIndexOf('.');
                jdbc.update("INSERT INTO drive_file_index(source,company,root,name,path,ext,size,last_modified,indexed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        source, company, root, file.name(), file.path(), dot < 0 ? "" : file.name().substring(dot + 1),
                        file.size(), file.lastModified() == null ? null : Timestamp.from(file.lastModified()), Timestamp.from(now));
            }
            jdbc.update("UPDATE drive_index_root_state SET status='SUCCESS',last_success_at=?,folder_count=?,file_count=?,error_code=NULL WHERE source=? AND company=? AND root=?",
                    Timestamp.from(now), folders, files.size(), source, company, root);
        });
    }
    public List<FmsDrivePort.Item> files(String source, String company, String root) {
        return transaction.execute(tx -> {
            if (!"SUCCESS".equals(state(source, company, root).status()))
                throw new FmsDriveException("Drive 인덱스가 미구축·갱신 중이거나 갱신에 실패했습니다. 인덱스를 갱신하세요.");
            return jdbc.query("SELECT name,path,size,last_modified FROM drive_file_index WHERE source=? AND company=? AND root=? ORDER BY path",
                    (rs, n) -> new FmsDrivePort.Item(rs.getString("name"), rs.getString("path"), false,
                            rs.getLong("size"), instant(rs.getTimestamp("last_modified"))), source, company, root);
        });
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
