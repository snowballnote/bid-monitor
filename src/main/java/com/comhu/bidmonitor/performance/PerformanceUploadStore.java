package com.comhu.bidmonitor.performance;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** File bytes and metadata belong to Biz Assist, never to the company DB or FMS. */
@Service
public class PerformanceUploadStore {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final Path root;
    public PerformanceUploadStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
            @Value("${performance.upload-directory:./data/performance-uploads}") String directory) {
        this.jdbc = jdbc;this.root = Path.of(directory).toAbsolutePath().normalize();
    }
    public record Stored(String id, String filename, String ext) { }
    private Path path(String id) {
        try { return root.resolve(UUID.fromString(id).toString() + ".bin"); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("등록 파일 ID를 확인하세요."); }
    }
    @Transactional
    public Stored save(String projectId, String entryId, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw new IllegalArgumentException("비어 있지 않은 20MB 이하 파일을 선택하세요.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").strip();
        if (name.isBlank() || name.length() > 2000) throw new IllegalArgumentException("파일명을 확인하세요.");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (ext.length() > 100) throw new IllegalArgumentException("파일 확장자를 확인하세요.");
        String id = UUID.randomUUID().toString();Path target = path(id);
        long size = 0;
        try {
            Files.createDirectories(root);
            try (var input = file.getInputStream();var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_BYTES) throw new IllegalArgumentException("20MB 이하 파일을 선택하세요.");
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) throw new IllegalArgumentException("비어 있는 파일은 등록할 수 없습니다.");
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            if (failure instanceof IOException io) throw new UncheckedIOException("파일 저장에 실패했습니다.", io);
            throw (RuntimeException) failure;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            }
        });
        jdbc.update("INSERT INTO performance_uploaded_file(id,project_id,entry_id,original_filename,file_ext,size_bytes) VALUES (?,?,?,?,?,?)",
                id, projectId, entryId, name, ext, size);
        return new Stored(id, name, ext);
    }
    public Stored require(String projectId, String entryId, String id) {
        path(id);
        return jdbc.query("SELECT id,original_filename,file_ext FROM performance_uploaded_file WHERE id=? AND project_id=? AND entry_id=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)), id, projectId, entryId).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("이 실적에 등록된 파일을 선택하세요."));
    }
    public InputStream open(String projectId, String entryId, String id) throws IOException {
        require(projectId, entryId, id);
        return Files.newInputStream(path(id));
    }
}
