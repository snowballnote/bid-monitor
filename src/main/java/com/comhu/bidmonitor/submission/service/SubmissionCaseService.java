package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.DocumentCandidate;
import com.comhu.bidmonitor.submission.domain.DocumentMatchLevel;
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
        List<String> keywords = extractor.searchKeywords(requirement.getDocumentName());
        String exactName = extractor.normalizeForMatch(requirement.getDocumentName());

        return fileSearchPort.searchByKeywords(keywords, CANDIDATE_LIMIT).stream()
                .map(file -> toCandidate(file, exactName, keywords))
                .sorted(candidateComparator())
                .toList();
    }

    @Transactional
    public List<SubmissionDocumentSelection> replaceSelections(Long caseId, List<SelectionChoice> choices) {
        get(caseId);
        List<SubmissionDocumentSelection> selections = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        Instant selectedAt = clock.instant();

        for (SelectionChoice choice : choices == null ? List.<SelectionChoice>of() : choices) {
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

    public record SelectionChoice(Long requirementId, Long fileId) {
    }
}
