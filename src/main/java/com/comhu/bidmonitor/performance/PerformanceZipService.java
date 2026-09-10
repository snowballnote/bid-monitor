package com.comhu.bidmonitor.performance;

import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class PerformanceZipService {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final PerformanceRepository repository;
    private final PerformanceFileContentPort content;
    private final DriveEvidenceService drive;
    private final PerformanceUploadStore uploads;

    public PerformanceZipService(PerformanceRepository repository, PerformanceFileContentPort content, DriveEvidenceService drive) {
        this(repository, content, drive, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PerformanceZipService(PerformanceRepository repository, PerformanceFileContentPort content, DriveEvidenceService drive, PerformanceUploadStore uploads) {
        this.repository = repository; this.content = content; this.drive = drive; this.uploads = uploads;
    }

    public byte[] download(String projectId) throws IOException {
        var entries = repository.entries(projectId).stream().filter(e -> e.info().selectedFileId() != null || e.info().selectedDriveFileId() != null || e.info().selectedUploadedFileId() != null).toList();
        if (entries.isEmpty()) throw new IllegalArgumentException("먼저 증빙파일을 선택하세요.");
        var names = new HashSet<String>();
        for (Entry entry : entries) {
            if (!names.add(filename(entry).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("ZIP 파일명이 중복됩니다. PPT 번호나 사업명을 수정하세요.");
            }
        }
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            long total = 0;
            byte[] buffer = new byte[8192];
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(filename(entry)));
                try (var stream = entry.info().selectedUploadedFileId() != null ? uploads.open(entry.projectId(), entry.id(), entry.info().selectedUploadedFileId()) : entry.info().selectedDriveFileId() != null ? drive.open(entry.info().selectedDriveFileId()) : content.open(entry.info().selectedFileId())) {
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("ZIP 원본 파일 합계는 100MB까지 지원합니다.");
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    static String filename(Entry entry) {
        var info = entry.info();
        String ext = entry.selectedExt();
        if (ext == null || ext.isBlank()) {
            String original = entry.selectedFilename();
            int dot = original == null ? -1 : original.lastIndexOf('.');
            ext = dot < 0 ? "" : original.substring(dot + 1);
        }
        ext = ext.replaceFirst("^\\.", "");
        if (!ext.matches("[a-zA-Z0-9]{0,16}")) ext = "";
        return safe(info.pptNumber()) + "_" + info.evidenceType().label + "_("
                + safe(info.client()) + ") " + safe(info.businessName()) + (ext.isEmpty() ? "" : "." + ext);
    }

    private static String safe(String value) {
        String result = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_")
                .replaceAll("\\.{2,}", "_").replaceAll("[. ]+$", "").strip();
        if (result.isEmpty()) result = "_";
        return result;
    }
}