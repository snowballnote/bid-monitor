package com.comhu.bidmonitor.performance;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class PerformanceZipService {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final PerformanceRepository repository;
    private final PerformanceEvidenceRepository evidences;
    private final PerformanceFileContentPort content;
    private final DriveEvidenceService drive;
    private final PerformanceUploadStore uploads;

    public PerformanceZipService(PerformanceRepository repository, PerformanceEvidenceRepository evidences,
                                 PerformanceFileContentPort content, DriveEvidenceService drive,
                                 PerformanceUploadStore uploads) {
        this.repository = repository;
        this.evidences = evidences;
        this.content = content;
        this.drive = drive;
        this.uploads = uploads;
    }

    public byte[] download(String projectId) throws IOException {
        var selected = new ArrayList<SelectedEvidence>();
        for (Entry entry : repository.entries(projectId)) {
            for (Evidence evidence : evidences.findByEntryId(entry.id())) {
                selected.add(new SelectedEvidence(entry, evidence));
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("먼저 증빙파일을 선택하세요.");

        var names = new HashSet<String>();
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            long total = 0;
            byte[] buffer = new byte[8192];
            for (SelectedEvidence selectedEvidence : selected) {
                Entry entry = selectedEvidence.entry();
                Evidence evidence = selectedEvidence.evidence();
                zip.putNextEntry(new ZipEntry(uniqueName(filename(entry, evidence), names)));
                try (var stream = evidence.selectedUploadedFileId() != null
                        ? uploads.open(entry.projectId(), entry.id(), evidence.selectedUploadedFileId())
                        : evidence.selectedDriveFileId() != null ? drive.open(evidence.selectedDriveFileId())
                        : content.open(evidence.selectedFileId())) {
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
        return filename(entry, entry.info().evidenceType(), entry.selectedFilename(), entry.selectedExt());
    }

    static String filename(Entry entry, Evidence evidence) {
        return filename(entry, evidence.evidenceType(), evidence.filename(), evidence.fileExt());
    }

    private static String filename(Entry entry, EvidenceType type, String original, String selectedExt) {
        var info = entry.info();
        String ext = selectedExt;
        if (ext == null || ext.isBlank()) {
            int dot = original == null ? -1 : original.lastIndexOf('.');
            ext = dot < 0 ? "" : original.substring(dot + 1);
        }
        ext = ext.replaceFirst("^\\.", "");
        if (!ext.matches("[a-zA-Z0-9]{0,16}")) ext = "";
        String numberPrefix = info.pptNumber() == null || info.pptNumber().isBlank()
                ? "" : safe(info.pptNumber()) + "_";
        return numberPrefix + type.label + "_(" + safe(info.client()) + ") "
                + safe(info.businessName()) + (ext.isEmpty() ? "" : "." + ext);
    }

    private static String uniqueName(String requested, HashSet<String> names) {
        String candidate = requested;
        int dot = requested.lastIndexOf('.');
        String stem = dot < 0 ? requested : requested.substring(0, dot);
        String extension = dot < 0 ? "" : requested.substring(dot);
        int suffix = 2;
        while (!names.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = stem + "_" + suffix++ + extension;
        }
        return candidate;
    }

    private static String safe(String value) {
        if (value == null) return "_";
        String result = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_")
                .replaceAll("\\.{2,}", "_").replaceAll("[. ]+$", "").strip();
        return result.isEmpty() ? "_" : result;
    }

    private record SelectedEvidence(Entry entry, Evidence evidence) { }
}
