package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** SELECT-only metadata lookup and read-only access under an explicitly configured NAS mount. */
@Component
public class NasPerformanceFileContentAdapter implements PerformanceFileContentPort {
    private final ObjectProvider<JdbcTemplate> company;
    private final String root;
    public NasPerformanceFileContentAdapter(
            @Qualifier("companyJdbcTemplate") ObjectProvider<JdbcTemplate> company,
            @Value("${performance.nas-root:}") String root) {
        this.company = company; this.root = root;
    }

    @Override
    public InputStream open(long fileId) throws IOException {
        try {
            if (root.isBlank() || company.getIfAvailable() == null) throw new IOException();
            var paths = company.getIfAvailable().query("""
                    SELECT storage_path FROM public.files
                    WHERE file_id = ? AND file_status = ? AND is_dir = ?
                    """, (rs, row) -> rs.getString("storage_path"), fileId, 0, false);
            if (paths.size() != 1 || paths.getFirst() == null || paths.getFirst().isBlank()) throw new IOException();
            Path allowed = Path.of(root).toRealPath();
            Path candidate = allowed.resolve(paths.getFirst()).normalize();
            if (!candidate.startsWith(allowed)) throw new IOException();
            Path real = candidate.toRealPath();
            if (!real.startsWith(allowed) || !Files.isRegularFile(real)) throw new IOException();
            return Files.newInputStream(real);
        } catch (Exception exception) {
            // Do not attach an exception containing JDBC credentials or a physical path.
            throw new IOException("선택 파일을 읽을 수 없습니다. 파일 상태와 NAS 연결을 확인하세요.");
        }
    }
}