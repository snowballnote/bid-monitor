package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.DocumentCandidate;
import com.comhu.bidmonitor.submission.domain.DocumentMatchLevel;
import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.domain.RequirementSourceType;
import com.comhu.bidmonitor.submission.domain.SubmissionCase;
import com.comhu.bidmonitor.submission.domain.SubmissionCaseStatus;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentSelection;
import com.comhu.bidmonitor.submission.domain.SubmissionPackage;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionRequirementRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionSelectionRepository;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort.CompanyFileMetadata;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort.PmsProject;
import com.comhu.bidmonitor.submission.port.RequiredDocumentFallbackPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 사업 선택부터 요구서류·후보·사용자 선택을 조합하되 회사 DB에는 어떠한 변경도 하지 않는다. */
@Service
public class SubmissionCaseService {

    private static final int CANDIDATE_LIMIT = 50;

    private final PmsProjectQueryPort projectQueryPort;
    private final CompanyFileSearchPort fileSearchPort;
    private final RequiredDocumentFallbackPort fallbackPort;
    private final SubmissionCaseRepository caseRepository;
    private final SubmissionRequirementRepository requirementRepository;
    private final SubmissionSelectionRepository selectionRepository;
    private final SubmissionRequirementExtractor extractor;
    private final Clock clock;

    public SubmissionCaseService(
            PmsProjectQueryPort projectQueryPort,
            CompanyFileSearchPort fileSearchPort,
            RequiredDocumentFallbackPort fallbackPort,
            SubmissionCaseRepository caseRepository,
            SubmissionRequirementRepository requirementRepository,
            SubmissionSelectionRepository selectionRepository,
            SubmissionRequirementExtractor extractor,
            Clock clock
    ) {
        this.projectQueryPort = projectQueryPort;
        this.fileSearchPort = fileSearchPort;
        this.fallbackPort = fallbackPort;
        this.caseRepository = caseRepository;
        this.requirementRepository = requirementRepository;
        this.selectionRepository = selectionRepository;
        this.extractor = extractor;
        this.clock = clock;
    }

    @Transactional
    public SubmissionCase create(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new IllegalArgumentException("projectId는 양수여야 합니다.");
        }
        var existing = caseRepository.findByProjectId(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }

        PmsProject project = projectQueryPort.findProjectById(projectId)
                .orElseThrow(() -> new SubmissionNotFoundException("회사 DB에서 사업을 찾을 수 없습니다."));
        Instant now = clock.instant();
        SubmissionCase savedCase = caseRepository.save(SubmissionCase.builder()
                .projectId(project.projectId())
                .projectPublicId(project.publicId())
                .projectCode(project.projectCode())
                .internalBizNo(project.internalBizNo())
                .projectName(project.noticeName())
                .bidNoticeNo(project.bidNoticeNo())
                .status(SubmissionCaseStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build());

        String organization = projectQueryPort.searchProjects(project.noticeName(), 50).stream()
                .filter(item -> projectId.equals(item.projectId())).map(PmsProjectQueryPort.PmsProjectSummary::organizationName)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        savedCase = savedCase.toBuilder().organizationName(organization).build();
        caseRepository.update(savedCase);
        List<SubmissionDocumentRequirement> requirements = extractor.fromRfpItems(
                savedCase.getId(),
                projectQueryPort.findRfpItems(projectId),
                now
        );
        if (requirements.isEmpty()) {
            requirements = extractor.fromG2bDocuments(
                    savedCase.getId(),
                    project.bidNoticeNo(),
                    fallbackPort.findRequiredDocuments(project.bidNoticeNo()),
                    now
            );
        }
        requirementRepository.saveAll(requirements);
        return savedCase;
    }

    /** PMS/RFP 없이 사용자가 고른 서류만으로 독립적인 제출 준비 작업을 만든다. */
    @Transactional
    public SubmissionCase createManual(List<ManualRequirement> requestedRequirements) {
        if (requestedRequirements == null || requestedRequirements.isEmpty()) {
            throw new IllegalArgumentException("필요한 제출서류를 하나 이상 선택해 주세요.");
        }
        if (requestedRequirements.size() > 30) {
            throw new IllegalArgumentException("제출서류는 한 번에 30개까지 선택할 수 있습니다.");
        }

        Instant now = clock.instant();
        SubmissionCase savedCase = caseRepository.save(SubmissionCase.builder()
                .projectName("직접 선택 제출서류")
                .status(SubmissionCaseStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build());

        Set<String> documentNames = new HashSet<>();
        List<SubmissionDocumentRequirement> requirements = requestedRequirements.stream()
                .map(requirement -> toManualRequirement(savedCase.getId(), requirement, documentNames, now))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("유효한 제출서류를 하나 이상 입력해 주세요.");
        }
        requirementRepository.saveAll(requirements);
        return savedCase;
    }

    public record ProjectSummary(Long id, Long projectId, String projectName, String organizationName,
                                 long prepared, long total, Instant updatedAt, java.time.LocalDate deadline) { }

    @Transactional(readOnly = true)
    public List<ProjectSummary> projects() {
        return caseRepository.findAll().stream().map(value -> {
            var requirements = requirementRepository.findBySubmissionCaseId(value.getId());
            var selected = selectionRepository.findBySubmissionCaseId(value.getId()).stream()
                    .map(SubmissionDocumentSelection::getRequirementId).collect(java.util.stream.Collectors.toSet());
            String performance = value.getPerformanceProjectId();
            boolean performanceReady = performance != null && caseRepository.performanceTotal(performance) > 0
                    && caseRepository.performanceMissing(performance) == 0;
            long ready = requirements.stream().filter(r -> requiresPerformanceSelection(r) ? performanceReady : selected.contains(r.getId())).count();
            return new ProjectSummary(value.getId(), value.getProjectId(), value.getProjectName(),
                    value.getProjectId() == null ? null : value.getOrganizationName(), ready, requirements.size(), value.getUpdatedAt(), value.getDeadline());
        }).toList();
    }

    @Transactional
    public SubmissionCase createProject(String name, Long pmsId, List<ManualRequirement> requirements, java.time.LocalDate deadline) {
        if (deadline == null) throw new IllegalArgumentException("마감일을 입력하세요.");
        var created = createProject(name, pmsId, requirements).toBuilder().deadline(deadline).build();
        caseRepository.update(created);
        return get(created.getId());
    }

    @Transactional
    public SubmissionCase updateProject(Long id, String name, boolean changePerformance, String performanceId,
                                        boolean initializeOnly, boolean changeDeadline, java.time.LocalDate deadline) {
        if (changeDeadline && deadline == null) throw new IllegalArgumentException("마감일을 입력하세요.");
        var updated = updateProject(id, name, changePerformance, performanceId, initializeOnly);
        if (changeDeadline) caseRepository.update(updated.toBuilder().deadline(deadline).build());
        return get(id);
    }

    @Transactional
    public void deleteProject(Long id) {
        caseRepository.lock(id);
        caseRepository.delete(id);
    }
    @Transactional
    public SubmissionCase createProject(String name, Long pmsId, List<ManualRequirement> requirements) {
        name = projectName(name);
        SubmissionCase value;
        if (pmsId != null) {
            value = create(pmsId);
        } else {
            Instant now = clock.instant();
            value = caseRepository.save(SubmissionCase.builder().projectName(name).status(SubmissionCaseStatus.DRAFT)
                    .createdAt(now).updatedAt(now).build());
        }
        value = value.toBuilder().projectName(name).updatedAt(clock.instant()).build();
        caseRepository.update(value);
        if (requirements != null) replaceRequirements(value.getId(), requirements);
        return get(value.getId());
    }

    @Transactional
    public SubmissionCase updateProject(Long id, String name, boolean changePerformance, String performanceId, boolean initializeOnly) {
        SubmissionCase old = caseRepository.lock(id);
        var builder = old.toBuilder().updatedAt(clock.instant());
        if (name != null) builder.projectName(projectName(name));
        if (changePerformance && (!initializeOnly || (!old.isPerformanceLinkInitialized() && old.getPerformanceProjectId() == null))) {
            if (performanceId != null) {
                if (!caseRepository.performanceProjectExists(performanceId)) throw new IllegalArgumentException("연결할 실적 프로젝트를 찾을 수 없습니다.");
                if (requirementRepository.findBySubmissionCaseId(id).stream().noneMatch(this::requiresPerformanceSelection))
                    throw new IllegalArgumentException("필요서류에 실적증명서를 먼저 선택하세요.");
            }
            builder.performanceProjectId(performanceId).performanceLinkInitialized(true);
        }
        caseRepository.update(builder.build());
        return get(id);
    }

    @Transactional
    public List<SubmissionDocumentRequirement> replaceRequirements(Long id, List<ManualRequirement> requested) {
        var project = caseRepository.lock(id);
        if (requested == null || requested.size() > 30) throw new IllegalArgumentException("필요서류는 30개 이하로 입력하세요.");
        var names = new HashSet<String>();
        Instant now = clock.instant();
        var incoming = requested.stream().map(r -> toManualRequirement(id, r, names, now))
                .filter(java.util.Objects::nonNull).toList();
        var existing = requirementRepository.findBySubmissionCaseId(id);
        var kept = new HashSet<Long>();
        var additions = new ArrayList<SubmissionDocumentRequirement>();
        for (var next : incoming) {
            var match = existing.stream().filter(r -> r.getCategory() == next.getCategory()
                    && normalizeManualName(r.getDocumentName()).equals(normalizeManualName(next.getDocumentName()))).findFirst();
            if (match.isPresent()) kept.add(match.get().getId()); else additions.add(next);
        }
        for (var old : existing) if (!kept.contains(old.getId())) requirementRepository.delete(id, old.getId());
        requirementRepository.saveAll(additions);
        caseRepository.update(project.toBuilder().updatedAt(now).build());
        return requirementRepository.findBySubmissionCaseId(id);
    }

    private String projectName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 2000)
            throw new IllegalArgumentException("프로젝트명은 1자 이상 2000자 이하로 입력하세요.");
        return name.strip();
    }
    public SubmissionCase get(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new SubmissionNotFoundException("제출서류 작업을 찾을 수 없습니다."));
    }

    public List<SubmissionDocumentRequirement> getRequirements(Long caseId) {
        get(caseId);
        return requirementRepository.findBySubmissionCaseId(caseId);
    }

    public List<DocumentCandidate> findCandidates(Long caseId, Long requirementId) {
        SubmissionDocumentRequirement requirement = requireRequirement(caseId, requirementId);
        if (requiresPerformanceSelection(requirement)) {
            return List.of();
        }
        List<String> keywords = extractor.searchKeywords(requirement.getDocumentName());
        String exactName = extractor.normalizeForMatch(requirement.getDocumentName());

        return fileSearchPort.searchByKeywords(keywords, CANDIDATE_LIMIT).stream()
                .map(file -> toCandidate(file, exactName, keywords))
                .sorted(candidateComparator())
                .toList();
    }

    @Transactional
    public List<SubmissionDocumentSelection> replaceSelections(Long caseId, List<SelectionChoice> choices) {
        var project = caseRepository.lock(caseId);
        List<SubmissionDocumentSelection> selections = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        var saved=selectionRepository.findBySubmissionCaseId(caseId);
        Instant selectedAt = clock.instant();

        for (SelectionChoice choice : choices == null ? List.<SelectionChoice>of() : choices) {
            if(choice!=null && choice.requirementId()!=null && choice.fileId()==null) {
                var local=saved.stream().filter(item->choice.requirementId().equals(item.getRequirementId()) && item.getUploadedFileId()!=null).findFirst();
                if(local.isPresent()){if(dedup.add(choice.requirementId()+":local"))selections.add(local.get());continue;}
            }
            if (choice == null || choice.requirementId() == null || choice.fileId() == null
                    || choice.requirementId() <= 0 || choice.fileId() <= 0) {
                throw new InvalidSubmissionSelectionException("요구서류 ID와 파일 ID는 양수여야 합니다.");
            }
            SubmissionDocumentRequirement requirement = requireRequirement(caseId, choice.requirementId());
            String dedupKey = requirement.getId() + ":" + choice.fileId();
            if (!dedup.add(dedupKey)) {
                continue;
            }
            CompanyFileMetadata file = fileSearchPort.findActiveFileById(choice.fileId())
                    .orElseThrow(() -> new InvalidSubmissionSelectionException("선택한 활성 파일을 찾을 수 없습니다."));
            selections.add(SubmissionDocumentSelection.builder()
                    .submissionCaseId(caseId)
                    .requirementId(requirement.getId())
                    .fileId(file.fileId())
                    .filePublicId(file.publicId())
                    .originalFilename(file.originalFilename())
                    .fileExt(file.fileExt())
                    .fileModifiedAt(file.fileModifiedAt())
                    .fileUpdatedAt(file.updatedAt())
                    .selectedAt(selectedAt)
                    .build());
        }
        caseRepository.update(project.toBuilder().updatedAt(selectedAt).build());
        return selectionRepository.replaceForCase(caseId, selections);
    }

    public SubmissionPackage getPackage(Long caseId) {
        return SubmissionPackage.builder()
                .submissionCase(get(caseId))
                .requirements(requirementRepository.findBySubmissionCaseId(caseId))
                .selections(selectionRepository.findBySubmissionCaseId(caseId))
                .build();
    }

    private SubmissionDocumentRequirement requireRequirement(Long caseId, Long requirementId) {
        SubmissionDocumentRequirement requirement = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new SubmissionNotFoundException("제출 요구서류를 찾을 수 없습니다."));
        if (!caseId.equals(requirement.getSubmissionCaseId())) {
            throw new InvalidSubmissionSelectionException("요구서류가 해당 제출 작업에 속하지 않습니다.");
        }
        return requirement;
    }

    private DocumentCandidate toCandidate(CompanyFileMetadata file, String exactName, List<String> keywords) {
        String normalizedFilename = extractor.normalizeForMatch(file.originalFilename());
        boolean exact = !exactName.isBlank() && normalizedFilename.contains(exactName);
        List<String> matchedKeywords = keywords.stream()
                .filter(keyword -> normalizedFilename.contains(extractor.normalizeForMatch(keyword)))
                .toList();
        DocumentMatchLevel level = exact ? DocumentMatchLevel.EXACT : DocumentMatchLevel.RECOMMENDED;
        List<String> reasons = exact
                ? List.of("파일명에 요구서류명이 포함됨")
                : matchedKeywords.stream().map(keyword -> "문서 유형 키워드 일치: " + keyword).toList();
        return DocumentCandidate.builder()
                .fileId(file.fileId())
                .publicId(file.publicId())
                .originalFilename(file.originalFilename())
                .fileExt(file.fileExt())
                .fileModifiedAt(file.fileModifiedAt())
                .updatedAt(file.updatedAt())
                .matchLevel(level)
                .matchReasons(reasons)
                .build();
    }

    private Comparator<DocumentCandidate> candidateComparator() {
        return Comparator.comparing(DocumentCandidate::getMatchLevel)
                .thenComparing(
                        candidate -> candidate.getFileModifiedAt() != null
                                ? candidate.getFileModifiedAt() : candidate.getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                );
    }

    private SubmissionDocumentRequirement toManualRequirement(
            Long caseId,
            ManualRequirement requirement,
            Set<String> documentNames,
            Instant now
    ) {
        if (requirement == null || requirement.category() == null) {
            throw new IllegalArgumentException("제출서류 카테고리가 필요합니다.");
        }
        String name = requirement.documentName() == null ? "" : requirement.documentName().trim();
        if (name.isEmpty() || name.length() > 200) {
            throw new IllegalArgumentException("제출서류명은 1자 이상 200자 이하로 입력해 주세요.");
        }
        if (!documentNames.add(normalizeManualName(name))) {
            return null;
        }
        return SubmissionDocumentRequirement.builder()
                .submissionCaseId(caseId)
                .category(requirement.category())
                .documentName(name)
                .required(true)
                .evidenceText("사용자가 직접 선택한 제출서류")
                .sourceType(RequirementSourceType.USER_SELECTED)
                .sourceReference(requirement.sourceReference())
                .createdAt(now)
                .build();
    }

    private String normalizeManualName(String name) {
        return name.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private boolean requiresPerformanceSelection(SubmissionDocumentRequirement requirement) {
        return requirement.getCategory() == RequirementCategory.PERFORMANCE
                && ("실적증명서".equals(requirement.getDocumentName().trim()) || "PERFORMANCE".equals(requirement.getSourceReference()));
    }

    public record SelectionChoice(Long requirementId, Long fileId) {
    }

    public record ManualRequirement(
            RequirementCategory category,
            String documentName,
            String sourceReference
    ) {
    }
}
