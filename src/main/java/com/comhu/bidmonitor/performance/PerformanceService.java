package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;

import java.util.List;
import java.util.regex.Pattern;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

@Service
public class PerformanceService {
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{4})[.년/\\-]\\s*(\\d{1,2})(?:[.월/\\-]\\s*(\\d{1,2})(?!\\d))?");
    private final PerformanceRepository repository;
    private final PerformanceTableParser parser;
    private final CompanyFileSearchPort files;
    private final DriveEvidenceService drive;
    private final PerformanceUploadStore uploads;

    public PerformanceService(PerformanceRepository repository, PerformanceTableParser parser, CompanyFileSearchPort files, DriveEvidenceService drive) {
        this(repository, parser, files, drive, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PerformanceService(PerformanceRepository repository, PerformanceTableParser parser, CompanyFileSearchPort files, DriveEvidenceService drive, PerformanceUploadStore uploads) {
        this.uploads = uploads;
        this.repository = repository;
        this.parser = parser;
        this.files = files;
        this.drive = drive;
    }

    public List<Project> projects() { return repository.projects(); }
    public Project project(String id) { return repository.project(id); }
    public List<Entry> entries(String id) { return repository.entries(id); }
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
                if (row.cells().size() != 5) throw new IllegalArgumentException("번호 / 사업명 / 사업기간 / 계약금액 / 발주처의 5개 셀이 필요합니다.");
                var c = row.cells();
                EntryInput input = normalize(new EntryInput(c.get(0), c.get(1), c.get(2), c.get(3), c.get(4),
                        null, null, null, KitcStatus.NEEDED, null, null));
                saved.add(repository.insert(projectId, input));
            } catch (IllegalArgumentException exception) {
                errors.add(new RowError(row.row(), row.cells(), exception.getMessage()));
            } catch (DuplicateKeyException exception) {
                errors.add(new RowError(row.row(), row.cells(), "이미 저장된 PPT 번호입니다. 기존 실적을 수정하세요."));
            }
        }
        return new ImportResult(saved, errors);
    }

    @Transactional
    public Entry update(String projectId, String id, EntryInput raw) {
        Entry old = repository.entry(projectId, id);
        EntryInput input = normalize(raw);
        String filename = null;
        String ext = null;
        if (input.selectedUploadedFileId() != null) {
            var uploaded = uploads.require(projectId, id, input.selectedUploadedFileId());
            filename = uploaded.filename();ext = uploaded.ext();
        } else if (input.selectedDriveFileId() != null) {
            if (input.selectedDriveFileId().equals(old.info().selectedDriveFileId())
                    && input.evidenceType() == old.info().evidenceType()) {
                filename = old.selectedFilename(); ext = old.selectedExt();
            } else {
                var selected = drive.selectable(old, input.selectedDriveFileId(), input.evidenceType());
                filename = selected.filename(); ext = selected.ext();
                input = new EntryInput(input.pptNumber(), input.businessName(), input.businessPeriod(),
                        input.contractAmount(), input.client(), input.businessStatus(), input.selectedFileId(),
                        input.evidenceType(), input.kitcStatus(), input.requestedAt(), input.repliedAt(),
                        selected.id(), input.selectedUploadedFileId());
            }
        } else if (input.selectedFileId() != null) {
            if (input.selectedFileId().equals(old.info().selectedFileId())) {
                filename = old.selectedFilename(); ext = old.selectedExt();
            } else {
                var file = files.findActiveFileById(input.selectedFileId())
                        .orElseThrow(() -> new IllegalArgumentException("연결할 수 있는 활성 파일이 아닙니다."));
                filename = file.originalFilename(); ext = file.fileExt();
            }
        }
        return repository.update(projectId, id, input, filename, ext);
    }

    @Transactional
    public Entry upload(String projectId, String id, org.springframework.web.multipart.MultipartFile file, EvidenceType type) {
        var old = repository.entry(projectId, id);
        if (type == null) throw new IllegalArgumentException("증빙유형을 선택하세요.");
        if (old.resolvedStatus() == BusinessStatus.IN_PROGRESS && type != EvidenceType.CONTRACT)
            throw new IllegalArgumentException("수행중 사업은 계약서를 등록하세요.");
        var stored = uploads.save(projectId, id, file);
        var info = old.info();
        return update(projectId, id, new EntryInput(info.pptNumber(), info.businessName(), info.businessPeriod(),
                info.contractAmount(), info.client(), info.businessStatus(), null, type, info.kitcStatus(),
                info.requestedAt(), info.repliedAt(), null, stored.id()));
    }

    public Recommendations candidates(String projectId, String id) {
        return drive.recommend(repository.entry(projectId, id));
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
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("사업기간 날짜를 확인하세요.");
            }
        }
        if (dates.size() != 2 || dates.getLast().isBefore(dates.getFirst())) {
            throw new IllegalArgumentException("사업기간은 시작~종료 날짜로 입력하거나 상세 수정에서 사업 상태를 지정하세요.");
        }
        return dates.getLast().isBefore(LocalDate.now()) ? BusinessStatus.COMPLETED : BusinessStatus.IN_PROGRESS;
    }

    private EntryInput normalize(EntryInput input) {
        if (input == null) throw new IllegalArgumentException("실적정보가 필요합니다.");
        String number = required(input.pptNumber(), 100, "PPT 번호");
        String name = required(input.businessName(), 1000, "사업명");
        String period = required(input.businessPeriod(), 500, "사업기간");
        String amount = required(input.contractAmount(), 200, "계약금액");
        String client = required(input.client(), 500, "발주처");
        if (input.kitcStatus() == null) throw new IllegalArgumentException("KITC 상태가 필요합니다.");
        int sources = (input.selectedFileId() == null ? 0 : 1) + (input.selectedDriveFileId() == null ? 0 : 1)
                + (input.selectedUploadedFileId() == null ? 0 : 1);
        if (sources > 1) throw new IllegalArgumentException("회사 파일, FMS 파일, 직접 등록 파일 중 하나만 선택하세요.");
        if (input.selectedDriveFileId() != null) {
            try { java.util.UUID.fromString(input.selectedDriveFileId()); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Drive 파일 선택 ID를 확인하세요."); }
        }
        if ((sources == 0) != (input.evidenceType() == null)
                || (input.selectedFileId() != null && input.selectedFileId() <= 0)) {
            throw new IllegalArgumentException("파일과 증빙유형을 함께 지정하세요.");
        }
        var normalized = new EntryInput(number, name, period, amount, client, input.businessStatus(),
                input.selectedFileId(), input.evidenceType(), input.kitcStatus(), input.requestedAt(), input.repliedAt(), input.selectedDriveFileId(), input.selectedUploadedFileId());
        if (resolveStatus(normalized) == BusinessStatus.IN_PROGRESS && input.evidenceType() == EvidenceType.CERTIFICATE) {
            throw new IllegalArgumentException("수행중 사업은 계약서를 연결하세요.");
        }
        boolean validDates = switch (input.kitcStatus()) {
            case NEEDED -> input.requestedAt() == null && input.repliedAt() == null;
            case REQUESTED -> input.requestedAt() != null && input.repliedAt() == null;
            case RECEIVED -> input.requestedAt() != null && input.repliedAt() != null
                    && !input.repliedAt().isBefore(input.requestedAt());
        };
        if (!validDates) throw new IllegalArgumentException("KITC 상태에 맞는 요청일·회신일을 직접 입력하세요. 회신일은 요청일 이후여야 합니다.");
        return normalized;
    }

    private void validateProject(ProjectInput input) {
        if (input == null || input.deadline() == null) throw new IllegalArgumentException("프로젝트명과 마감일이 필요합니다.");
        required(input.name(), 500, "프로젝트명");
    }
    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + "을(를) 확인하세요.");
        return value.strip().replaceAll("\\s+", " ");
    }
}
