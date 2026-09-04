package com.comhu.bidmonitor.submission.service;

import com.comhu.bidmonitor.submission.domain.DocumentMatchLevel;
import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.domain.RequirementSourceType;
import com.comhu.bidmonitor.submission.domain.SubmissionCase;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionRequirementRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionSelectionRepository;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import com.comhu.bidmonitor.submission.port.RequiredDocumentFallbackPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionCaseServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-03T01:00:00Z");

    private PmsProjectQueryPort projectQueryPort;
    private CompanyFileSearchPort fileSearchPort;
    private RequiredDocumentFallbackPort fallbackPort;
    private SubmissionCaseRepository caseRepository;
    private SubmissionRequirementRepository requirementRepository;
    private SubmissionSelectionRepository selectionRepository;
    private SubmissionCaseService service;

    @BeforeEach
    void setUp() {
        projectQueryPort = mock(PmsProjectQueryPort.class);
        fileSearchPort = mock(CompanyFileSearchPort.class);
        fallbackPort = mock(RequiredDocumentFallbackPort.class);
        caseRepository = mock(SubmissionCaseRepository.class);
        requirementRepository = mock(SubmissionRequirementRepository.class);
        selectionRepository = mock(SubmissionSelectionRepository.class);
        service = new SubmissionCaseService(
                projectQueryPort,
                fileSearchPort,
                fallbackPort,
                caseRepository,
                requirementRepository,
                selectionRepository,
                new SubmissionRequirementExtractor(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsRequirementsFromPmsRfpBeforeUsingG2bFallback() {
        stubNewProject();
        when(projectQueryPort.findRfpItems(101L)).thenReturn(List.of(
                new PmsProjectQueryPort.PmsRfpItem(
                        501L,
                        "제출서류",
                        "- 사업자등록증 1부\n- 보안서약서",
                        null
                )
        ));

        SubmissionCase created = service.create(101L);

        assertThat(created.getId()).isEqualTo(10L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubmissionDocumentRequirement>> captor = ArgumentCaptor.forClass(List.class);
        verify(requirementRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(SubmissionDocumentRequirement::getDocumentName)
                .containsExactly("사업자등록증", "보안서약서");
        assertThat(captor.getValue())
                .allMatch(requirement -> requirement.getSourceType() == RequirementSourceType.PMS_RFP_ITEM);
        verify(fallbackPort, never()).findRequiredDocuments(any());
    }

    @Test
    void usesExistingG2bDocumentAnalysisOnlyWhenRfpHasNoRequirement() {
        stubNewProject();
        when(projectQueryPort.findRfpItems(101L)).thenReturn(List.of());
        when(fallbackPort.findRequiredDocuments("20260903-00"))
                .thenReturn(List.of("법인등기사항전부증명서", "실적증명서"));

        service.create(101L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubmissionDocumentRequirement>> captor = ArgumentCaptor.forClass(List.class);
        verify(requirementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allMatch(requirement -> requirement.getSourceType() == RequirementSourceType.G2B_DOCUMENT_ANALYSIS);
    }

    @Test
    void ranksExactFilenameMatchesBeforeRecommendedAndNewestWithinLevel() {
        SubmissionDocumentRequirement requirement = SubmissionDocumentRequirement.builder()
                .id(20L)
                .submissionCaseId(10L)
                .category(RequirementCategory.COMPANY_GENERAL)
                .documentName("사업자등록증")
                .required(true)
                .sourceType(RequirementSourceType.PMS_RFP_ITEM)
                .createdAt(NOW)
                .build();
        when(requirementRepository.findById(20L)).thenReturn(Optional.of(requirement));
        when(fileSearchPort.searchByKeywords(any(), any(Integer.class))).thenReturn(List.of(
                file(1L, "사업자등록증_구본.pdf", NOW.minusSeconds(300)),
                file(2L, "사업자등록증_최신.pdf", NOW.minusSeconds(100)),
                file(3L, "법인 공통서류.pdf", NOW)
        ));

        var candidates = service.findCandidates(10L, 20L);

        assertThat(candidates).extracting(candidate -> candidate.getFileId())
                .containsExactly(2L, 1L, 3L);
        assertThat(candidates).extracting(candidate -> candidate.getMatchLevel())
                .containsExactly(DocumentMatchLevel.EXACT, DocumentMatchLevel.EXACT, DocumentMatchLevel.RECOMMENDED);
    }

    @Test
    void createsManualRequirementsWithoutReadingPmsAndDefersPerformanceSearch() {
        when(caseRepository.save(any())).thenAnswer(invocation -> {
            SubmissionCase value = invocation.getArgument(0);
            return value.toBuilder().id(30L).build();
        });

        SubmissionCase created = service.createManual(List.of(
                new SubmissionCaseService.ManualRequirement(
                        RequirementCategory.COMPANY_GENERAL, "사업자등록증", "BUSINESS_REGISTRATION"
                ),
                new SubmissionCaseService.ManualRequirement(
                        RequirementCategory.PERFORMANCE, "실적증명서", "PERFORMANCE"
                )
        ));

        assertThat(created.getProjectId()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubmissionDocumentRequirement>> captor = ArgumentCaptor.forClass(List.class);
        verify(requirementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(SubmissionDocumentRequirement::getSourceType)
                .containsOnly(RequirementSourceType.USER_SELECTED);
        verify(projectQueryPort, never()).findProjectById(any());

        SubmissionDocumentRequirement performance = captor.getValue().get(1).toBuilder().id(32L).build();
        when(requirementRepository.findById(32L)).thenReturn(Optional.of(performance));
        assertThat(service.findCandidates(30L, 32L)).isEmpty();
        verify(fileSearchPort, never()).searchByKeywords(any(), any(Integer.class));
    }

    private void stubNewProject() {
        when(caseRepository.findByProjectId(101L)).thenReturn(Optional.empty());
        when(projectQueryPort.findProjectById(101L)).thenReturn(Optional.of(
                new PmsProjectQueryPort.PmsProject(
                        101L,
                        UUID.randomUUID(),
                        "P-101",
                        "BIZ-101",
                        "테스트 사업",
                        "20260903-00"
                )
        ));
        when(caseRepository.save(any())).thenAnswer(invocation -> {
            SubmissionCase value = invocation.getArgument(0);
            return value.toBuilder().id(10L).build();
        });
    }

    private CompanyFileSearchPort.CompanyFileMetadata file(Long id, String filename, Instant modifiedAt) {
        return new CompanyFileSearchPort.CompanyFileMetadata(
                id,
                UUID.randomUUID(),
                filename,
                "pdf",
                modifiedAt,
                modifiedAt
        );
    }
}
