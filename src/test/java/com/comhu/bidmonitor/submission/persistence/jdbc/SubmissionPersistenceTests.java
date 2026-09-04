package com.comhu.bidmonitor.submission.persistence.jdbc;

import com.comhu.bidmonitor.submission.domain.RequirementCategory;
import com.comhu.bidmonitor.submission.domain.CommonDocumentType;
import com.comhu.bidmonitor.submission.domain.DocumentRefreshPolicy;
import com.comhu.bidmonitor.submission.domain.RequirementSourceType;
import com.comhu.bidmonitor.submission.domain.SubmissionCase;
import com.comhu.bidmonitor.submission.domain.SubmissionCaseStatus;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentRequirement;
import com.comhu.bidmonitor.submission.domain.SubmissionDocumentSelection;
import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import com.comhu.bidmonitor.submission.persistence.CommonSubmissionDocumentRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionRequirementRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionSelectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:submission-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "company-db.enabled=false",
        "external-notice.scheduler.enabled=false"
})
class SubmissionPersistenceTests {

    private static final Instant NOW = Instant.parse("2026-09-03T01:02:03Z");

    @Autowired
    private SubmissionCaseRepository caseRepository;

    @Autowired
    private SubmissionRequirementRepository requirementRepository;

    @Autowired
    private SubmissionSelectionRepository selectionRepository;

    @Autowired
    private CommonSubmissionDocumentRepository commonDocumentRepository;

    @Test
    void initializesAdministrativeCommonDocumentPolicies() {
        assertThat(commonDocumentRepository.findAllActive()).hasSize(12);
        assertThat(commonDocumentRepository.findByDocumentType(CommonDocumentType.BUSINESS_REGISTRATION))
                .get().extracting(document -> document.getRefreshPolicy())
                .isEqualTo(DocumentRefreshPolicy.NONE);
        assertThat(commonDocumentRepository.findByDocumentType(CommonDocumentType.CORPORATE_SEAL_CERTIFICATE))
                .get().satisfies(document -> {
                    assertThat(document.getRefreshPolicy()).isEqualTo(DocumentRefreshPolicy.PERIODIC);
                    assertThat(document.getRefreshIntervalMonths()).isEqualTo(3);
                });
        assertThat(commonDocumentRepository.findByDocumentType(CommonDocumentType.PIA_INSTITUTION_CERTIFICATE))
                .get().extracting(document -> document.getRefreshPolicy())
                .isEqualTo(DocumentRefreshPolicy.EXPIRATION_BASED);
    }

    @Test
    void storesCaseRequirementsAndSafeFileSelectionSnapshotsInPrimaryH2() {
        SubmissionCase submissionCase = caseRepository.save(SubmissionCase.builder()
                .projectId(7001L)
                .projectPublicId(UUID.randomUUID())
                .projectCode("P-7001")
                .internalBizNo("BIZ-7001")
                .projectName("제출서류 테스트 사업")
                .bidNoticeNo("20260903-00")
                .status(SubmissionCaseStatus.DRAFT)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
        SubmissionDocumentRequirement requirement = requirementRepository.saveAll(List.of(
                SubmissionDocumentRequirement.builder()
                        .submissionCaseId(submissionCase.getId())
                        .category(RequirementCategory.COMPANY_GENERAL)
                        .documentName("사업자등록증")
                        .required(true)
                        .evidenceText("사업자등록증 1부 제출")
                        .sourceType(RequirementSourceType.PMS_RFP_ITEM)
                        .sourceReference("9001")
                        .createdAt(NOW)
                        .build()
        )).getFirst();

        List<SubmissionDocumentSelection> selections = selectionRepository.replaceForCase(
                submissionCase.getId(),
                List.of(SubmissionDocumentSelection.builder()
                        .submissionCaseId(submissionCase.getId())
                        .requirementId(requirement.getId())
                        .fileId(8001L)
                        .filePublicId(UUID.randomUUID())
                        .originalFilename("사업자등록증.pdf")
                        .fileExt("pdf")
                        .fileModifiedAt(NOW.minusSeconds(60))
                        .fileUpdatedAt(NOW)
                        .selectedAt(NOW)
                        .build())
        );

        assertThat(caseRepository.findById(submissionCase.getId())).isPresent();
        assertThat(requirementRepository.findBySubmissionCaseId(submissionCase.getId()))
                .extracting(SubmissionDocumentRequirement::getDocumentName)
                .containsExactly("사업자등록증");
        assertThat(selections).singleElement().satisfies(selection -> {
            assertThat(selection.getFileId()).isEqualTo(8001L);
            assertThat(selection.getOriginalFilename()).isEqualTo("사업자등록증.pdf");
        });
    }

    @Test
    void projectCanOwnOnlyOneSubmissionCase() {
        SubmissionCase first = caseRepository.save(caseValue(7002L));
        SubmissionCase second = caseRepository.save(caseValue(7002L));

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    private SubmissionCase caseValue(Long projectId) {
        return SubmissionCase.builder()
                .projectId(projectId)
                .projectPublicId(UUID.randomUUID())
                .projectCode("P-" + projectId)
                .internalBizNo("BIZ-" + projectId)
                .projectName("중복 방지 테스트")
                .status(SubmissionCaseStatus.DRAFT)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
