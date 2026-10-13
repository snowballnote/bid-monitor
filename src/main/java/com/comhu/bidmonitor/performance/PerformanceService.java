package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class PerformanceService {
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{4})[.년/\\-]\\s*(\\d{1,2})(?:[.월/\\-]\\s*(\\d{1,2})(?!\\d))?");
    private final PerformanceRepository repository;
    private final PerformanceEvidenceRepository evidences;
    private final PerformanceTableParser parser;
    private final CompanyFileSearchPort files;
    private final PerformanceFileContentPort content;
    private final DriveEvidenceService drive;
    private final PerformanceUploadStore uploads;

    public PerformanceService(PerformanceRepository repository, PerformanceEvidenceRepository evidences,
                              PerformanceTableParser parser, CompanyFileSearchPort files,
                              PerformanceFileContentPort content, DriveEvidenceService drive,
                              PerformanceUploadStore uploads) {
        this.repository = repository;
        this.evidences = evidences;
        this.parser = parser;
        this.files = files;
        this.content = content;
        this.drive = drive;
        this.uploads = uploads;
    }

    public List<Project> projects() { return repository.projects(); }
    public Project project(String id) { return repository.project(id); }
    public List<Entry> entries(String projectId) {
        return repository.entries(projectId).stream().map(this::withEvidences).toList();
    }
    public Project create(ProjectInput input) { validateProject(input); return repository.create(input); }
    public Project updateProject(String id, ProjectInput input) {
        validateProject(input); return repository.updateProject(id, input);
    }

    @Transactional
    public ImportResult paste(String projectId, PasteInput paste) {
        repository.project(projectId);
        List<Entry> saved = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (ParsedRow row : parser.parse(paste)) {
            try {
                if (row.error() != null) throw new IllegalArgumentException(row.error());
                if (row.cells().size() != 4) throw new IllegalArgumentException("사업명 / 발주기관 / 사업기간 / 사업금액의 4개 값이 필요합니다.");
                var c = row.cells();
                EntryInput input = normalizeMetadata(new EntryInput(null, c.get(0), c.get(2), c.get(3), c.get(1),
                        null, null, null, KitcStatus.NEEDED, null, null));
                if (repository.existsByPerformanceFields(projectId, input)) {
                    throw new IllegalArgumentException("동일한 수행실적이 이미 저장되어 있습니다.");
                }
                saved.add(withEvidences(repository.insert(projectId, input)));
            } catch (IllegalArgumentException exception) {
                errors.add(new RowError(row.row(), row.cells(), exception.getMessage()));
            }
        }
        return new ImportResult(saved, errors);
    }

    @Transactional
    public Entry update(String projectId, String entryId, EntryInput raw) {
        Entry old = entry(projectId, entryId);
        EntryInput metadata = normalizeMetadata(raw);
        repository.update(projectId, entryId, metadata);
        EvidenceInput requested = raw == null ? null : new EvidenceInput(raw.selectedFileId(),
                raw.selectedDriveFileId(), raw.selectedUploadedFileId(), raw.evidenceType());
        validateOptionalEvidenceInput(requested);
        if (hasSource(requested) && resolveStatus(metadata) == BusinessStatus.IN_PROGRESS
                && requested.evidenceType() == EvidenceType.CERTIFICATE) {
            throw new IllegalArgumentException("수행 중 사업에는 계약서를 연결하세요.");
        }
        Evidence primary = evidences.findByEntryId(entryId).stream().findFirst().orElse(null);
        if (hasSource(requested) && !sameSelection(primary, requested)) {
            ResolvedEvidence resolved = resolveEvidence(old, requested);
            if (primary == null) evidences.add(entryId, resolved.input(), resolved.filename(), resolved.fileExt());
            else evidences.update(entryId, primary.id(), resolved.input(), resolved.filename(), resolved.fileExt());
        } else if (!hasSource(requested) && primary != null) {
            evidences.delete(entryId, primary.id());
        }
        return entry(projectId, entryId);
    }

    @Transactional
    public Entry upload(String projectId, String entryId, MultipartFile file, EvidenceType type) {
        Entry owner = entry(projectId, entryId);
        requireEvidenceType(type);
        if (owner.resolvedStatus() == BusinessStatus.IN_PROGRESS && type != EvidenceType.CONTRACT) {
            throw new IllegalArgumentException("수행 중 사업에는 계약서를 등록하세요.");
        }
        var stored = uploads.save(projectId, entryId, file);
        var input = new EvidenceInput(null, null, stored.id(), type);
        Evidence primary = evidences.findByEntryId(entryId).stream().findFirst().orElse(null);
        if (primary == null) evidences.add(entryId, input, stored.filename(), stored.ext());
        else evidences.update(entryId, primary.id(), input, stored.filename(), stored.ext());
        return withEvidences(repository.entry(owner.projectId(), owner.id()));
    }

    @Transactional
    public Entry addEvidence(String projectId, String entryId, EvidenceInput input) {
        Entry owner = entry(projectId, entryId);
        ResolvedEvidence resolved = resolveEvidence(owner, input);
        evidences.add(entryId, resolved.input(), resolved.filename(), resolved.fileExt());
        return entry(projectId, entryId);
    }

    @Transactional
    public Entry uploadEvidence(String projectId, String entryId, MultipartFile file, EvidenceType type) {
        entry(projectId, entryId);
        requireEvidenceType(type);
        var stored = uploads.save(projectId, entryId, file);
        evidences.add(entryId, new EvidenceInput(null, null, stored.id(), type), stored.filename(), stored.ext());
        return entry(projectId, entryId);
    }

    @Transactional
    public Entry replaceEvidence(String projectId, String entryId, String evidenceId, EvidenceInput input) {
        Entry owner = entry(projectId, entryId);
        evidences.find(entryId, evidenceId);
        ResolvedEvidence resolved = resolveEvidence(owner, input);
        evidences.update(entryId, evidenceId, resolved.input(), resolved.filename(), resolved.fileExt());
        return entry(projectId, entryId);
    }

    @Transactional
    public Entry replaceEvidenceUpload(String projectId, String entryId, String evidenceId,
                                       MultipartFile file, EvidenceType type) {
        entry(projectId, entryId);
        evidences.find(entryId, evidenceId);
        requireEvidenceType(type);
        var stored = uploads.save(projectId, entryId, file);
        evidences.update(entryId, evidenceId, new EvidenceInput(null, null, stored.id(), type),
                stored.filename(), stored.ext());
        return entry(projectId, entryId);
    }

    @Transactional
    public Entry deleteEvidence(String projectId, String entryId, String evidenceId) {
        entry(projectId, entryId);
        evidences.delete(entryId, evidenceId);
        return entry(projectId, entryId);
    }

    public EvidenceDownload downloadEvidence(String projectId, String entryId, String evidenceId) throws IOException {
        entry(projectId, entryId);
        Evidence evidence = evidences.find(entryId, evidenceId);
        if (evidence.selectedFileId() != null) return new EvidenceDownload(evidence.filename(), content.open(evidence.selectedFileId()));
        if (evidence.selectedDriveFileId() != null) return new EvidenceDownload(evidence.filename(), drive.open(evidence.selectedDriveFileId()));
        return new EvidenceDownload(evidence.filename(), uploads.open(projectId, entryId, evidence.selectedUploadedFileId()));
    }

    public Recommendations candidates(String projectId, String id) { return drive.recommend(entry(projectId, id)); }

    private Entry entry(String projectId, String entryId) { return withEvidences(repository.entry(projectId, entryId)); }

    private Entry withEvidences(Entry base) {
        List<EvidenceView> views = evidences.findByEntryId(base.id()).stream().map(this::view).toList();
        EvidenceView first = views.stream().findFirst().orElse(null);
        EntryInput info = base.info();
        EntryInput projection = new EntryInput(info.pptNumber(), info.businessName(), info.businessPeriod(),
                info.contractAmount(), info.client(), info.businessStatus(),
                first == null ? null : first.selectedFileId(), first == null ? null : first.evidenceType(),
                info.kitcStatus(), info.requestedAt(), info.repliedAt(),
                first == null ? null : first.selectedDriveFileId(), first == null ? null : first.selectedUploadedFileId());
        return new Entry(base.id(), base.projectId(), projection,
                first == null ? null : first.filename(), first == null ? null : first.fileExt(),
                base.resolvedStatus(), views);
    }

    private EvidenceView view(Evidence evidence) {
        EvidenceSourceType source = evidence.selectedFileId() != null ? EvidenceSourceType.COMPANY_FILE
                : evidence.selectedDriveFileId() != null ? EvidenceSourceType.FMS_DRIVE : EvidenceSourceType.DIRECT_UPLOAD;
        return new EvidenceView(evidence.id(), evidence.evidenceType(), source, evidence.selectedFileId(),
                evidence.selectedDriveFileId(), evidence.selectedUploadedFileId(), evidence.filename(),
                evidence.fileExt(), evidence.createdAt(), evidence.updatedAt());
    }

    private ResolvedEvidence resolveEvidence(Entry owner, EvidenceInput input) {
        validateRequiredEvidenceInput(input);
        if (input.selectedUploadedFileId() != null) {
            var stored = uploads.require(owner.projectId(), owner.id(), input.selectedUploadedFileId());
            return new ResolvedEvidence(input, stored.filename(), stored.ext());
        }
        if (input.selectedDriveFileId() != null) {
            var selected = drive.selectable(owner, input.selectedDriveFileId(), input.evidenceType());
            return new ResolvedEvidence(new EvidenceInput(null, selected.id(), null, input.evidenceType()),
                    selected.filename(), selected.ext());
        }
        var file = files.findActiveFileById(input.selectedFileId())
                .orElseThrow(() -> new IllegalArgumentException("연결할 수 있는 활성 파일이 아닙니다."));
        return new ResolvedEvidence(input, file.originalFilename(), file.fileExt());
    }

    private void validateRequiredEvidenceInput(EvidenceInput input) {
        validateOptionalEvidenceInput(input);
        if (!hasSource(input)) throw new IllegalArgumentException("파일과 증빙유형을 함께 지정하세요.");
    }

    private void validateOptionalEvidenceInput(EvidenceInput input) {
        if (input == null) throw new IllegalArgumentException("증빙정보가 필요합니다.");
        int sources = (input.selectedFileId() == null ? 0 : 1) + (input.selectedDriveFileId() == null ? 0 : 1)
                + (input.selectedUploadedFileId() == null ? 0 : 1);
        if (sources > 1) throw new IllegalArgumentException("회사 파일, FMS 파일, 직접 업로드 파일 중 하나만 선택하세요.");
        if ((sources == 0) != (input.evidenceType() == null) || (input.selectedFileId() != null && input.selectedFileId() <= 0)) {
            throw new IllegalArgumentException("파일과 증빙유형을 함께 지정하세요.");
        }
        if (input.selectedDriveFileId() != null) requireUuid(input.selectedDriveFileId(), "FMS 파일 선택 ID");
        if (input.selectedUploadedFileId() != null) requireUuid(input.selectedUploadedFileId(), "업로드 파일 ID");
    }

    private void requireUuid(String value, String label) {
        try { UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException(label + "를 확인하세요."); }
    }

    private boolean hasSource(EvidenceInput input) {
        return input != null && (input.selectedFileId() != null || input.selectedDriveFileId() != null || input.selectedUploadedFileId() != null);
    }

    private boolean sameSelection(Evidence evidence, EvidenceInput input) {
        return evidence != null && evidence.evidenceType() == input.evidenceType()
                && Objects.equals(evidence.selectedFileId(), input.selectedFileId())
                && Objects.equals(evidence.selectedDriveFileId(), input.selectedDriveFileId())
                && Objects.equals(evidence.selectedUploadedFileId(), input.selectedUploadedFileId());
    }

    private void requireEvidenceType(EvidenceType type) {
        if (type == null) throw new IllegalArgumentException("증빙유형을 선택하세요.");
    }

    static BusinessStatus resolveStatus(EntryInput input) {
        if (input.businessStatus() != null) return input.businessStatus();
        if (input.businessPeriod().matches("(?s).*(수행중|수행 중|진행중|진행 중|현재|계속).*")) return BusinessStatus.IN_PROGRESS;
        var matcher = DATE.matcher(input.businessPeriod());
        List<LocalDate> dates = new ArrayList<>();
        while (matcher.find()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                dates.add(matcher.group(3) == null ? YearMonth.of(year, month).atEndOfMonth()
                        : LocalDate.of(year, month, Integer.parseInt(matcher.group(3))));
            } catch (RuntimeException exception) { throw new IllegalArgumentException("사업기간 날짜를 확인하세요."); }
        }
        if (dates.size() != 2 || dates.getLast().isBefore(dates.getFirst())) {
            throw new IllegalArgumentException("사업기간은 시작~종료 날짜로 입력하거나 상세 수정에서 사업 상태를 지정하세요.");
        }
        return dates.getLast().isBefore(LocalDate.now()) ? BusinessStatus.COMPLETED : BusinessStatus.IN_PROGRESS;
    }

    private EntryInput normalizeMetadata(EntryInput input) {
        if (input == null) throw new IllegalArgumentException("실적정보가 필요합니다.");
        String number = optional(input.pptNumber(), 100, "PPT 번호");
        String name = required(input.businessName(), 1000, "사업명");
        String period = required(input.businessPeriod(), 500, "사업기간");
        String amount = required(input.contractAmount(), 200, "계약금액");
        String client = required(input.client(), 500, "발주처");
        if (input.kitcStatus() == null) throw new IllegalArgumentException("KITC 상태가 필요합니다.");
        var normalized = new EntryInput(number, name, period, amount, client, input.businessStatus(),
                null, null, input.kitcStatus(), input.requestedAt(), input.repliedAt(), null, null);
        resolveStatus(normalized);
        boolean validDates = switch (input.kitcStatus()) {
            case NEEDED -> input.requestedAt() == null && input.repliedAt() == null;
            case REQUESTED -> input.requestedAt() != null && input.repliedAt() == null;
            case RECEIVED -> input.requestedAt() != null && input.repliedAt() != null && !input.repliedAt().isBefore(input.requestedAt());
        };
        if (!validDates) throw new IllegalArgumentException("KITC 상태에 맞는 요청일과 회신일을 입력하세요.");
        return normalized;
    }

    private void validateProject(ProjectInput input) {
        if (input == null || input.deadline() == null) throw new IllegalArgumentException("프로젝트명과 마감일이 필요합니다.");
        required(input.name(), 500, "프로젝트명");
    }
    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + "을 확인하세요.");
        return value.strip().replaceAll("\\s+", " ");
    }
    private String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw new IllegalArgumentException(label + "를 확인하세요.");
        return value.strip().replaceAll("\\s+", " ");
    }

    private record ResolvedEvidence(EvidenceInput input, String filename, String fileExt) { }
}
