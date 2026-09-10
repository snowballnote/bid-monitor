package com.comhu.bidmonitor.performance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PerformanceModels {
    private PerformanceModels() { }

    public enum BusinessStatus { COMPLETED, IN_PROGRESS }
    public enum EvidenceType {
        CERTIFICATE("실적증명서"), CONTRACT("계약서");
        public final String label;
        EvidenceType(String label) { this.label = label; }
    }
    public enum KitcStatus { NEEDED, REQUESTED, RECEIVED }
    public record Project(String id, String name, LocalDate deadline, long daysRemaining, String status) { }
    public record ProjectInput(String name, LocalDate deadline) { }
    public record EntryInput(String pptNumber, String businessName, String businessPeriod,
                             String contractAmount, String client, BusinessStatus businessStatus,
                             Long selectedFileId, EvidenceType evidenceType, KitcStatus kitcStatus,
                             LocalDate requestedAt, LocalDate repliedAt, String selectedDriveFileId, String selectedUploadedFileId) {
        public EntryInput(String pptNumber, String businessName, String businessPeriod, String contractAmount,
                          String client, BusinessStatus businessStatus, Long selectedFileId, EvidenceType evidenceType,
                          KitcStatus kitcStatus, LocalDate requestedAt, LocalDate repliedAt, String selectedDriveFileId) {
            this(pptNumber, businessName, businessPeriod, contractAmount, client, businessStatus,
                    selectedFileId, evidenceType, kitcStatus, requestedAt, repliedAt, selectedDriveFileId, null);
        }
        public EntryInput(String pptNumber, String businessName, String businessPeriod, String contractAmount,
                          String client, BusinessStatus businessStatus, Long selectedFileId, EvidenceType evidenceType,
                          KitcStatus kitcStatus, LocalDate requestedAt, LocalDate repliedAt) {
            this(pptNumber, businessName, businessPeriod, contractAmount, client, businessStatus,
                    selectedFileId, evidenceType, kitcStatus, requestedAt, repliedAt, null);
        }
    }
    public record Entry(String id, String projectId, EntryInput info, String selectedFilename,
                        String selectedExt, BusinessStatus resolvedStatus) { }
    public record PasteInput(String text, String html) { }
    public record RowError(int row, List<String> cells, String message) { }
    public record ParsedRow(int row, List<String> cells, String error) { }
    public record ImportResult(List<Entry> saved, List<RowError> errors) { }
    public record EvidenceFile(Long fileId, String driveFileId, String originalFilename, String fileExt,
                               long size, Instant lastModified) { }
    public record Candidate(EvidenceFile file, EvidenceType evidenceType, String reason) { }
    public record Recommendations(List<Candidate> candidates, String nextAction) { }
}
