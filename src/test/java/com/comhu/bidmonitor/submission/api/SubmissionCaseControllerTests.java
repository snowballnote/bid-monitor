package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.persistence.SubmissionCaseRepository;
import com.comhu.bidmonitor.submission.persistence.SubmissionRequirementRepository;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.port.PmsProjectQueryPort;
import com.comhu.bidmonitor.submission.port.RequiredDocumentFallbackPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:submission-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "company-db.enabled=false",
        "external-notice.scheduler.enabled=false"
})
class SubmissionCaseControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionCaseRepository caseRepository;

    @Autowired
    private SubmissionRequirementRepository requirementRepository;

    @MockitoBean
    private PmsProjectQueryPort projectQueryPort;

    @MockitoBean
    private CompanyFileSearchPort fileSearchPort;

    @MockitoBean
    private RequiredDocumentFallbackPort fallbackPort;

    @Test
    void supportsCaseRequirementCandidateSelectionAndPackageFlow() throws Exception {
        UUID projectPublicId = UUID.randomUUID();
        when(projectQueryPort.findProjectById(301L)).thenReturn(Optional.of(
                new PmsProjectQueryPort.PmsProject(
                        301L,
                        projectPublicId,
                        "P-301",
                        "BIZ-301",
                        "API 테스트 사업",
                        "20260903-01"
                )
        ));
        when(projectQueryPort.findRfpItems(301L)).thenReturn(List.of(
                new PmsProjectQueryPort.PmsRfpItem(401L, "제출서류", "사업자등록증 1부", null)
        ));

        mockMvc.perform(post("/api/submission-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":301}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(301))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        Long caseId = caseRepository.findByProjectId(301L).orElseThrow().getId();
        Long requirementId = requirementRepository.findBySubmissionCaseId(caseId).getFirst().getId();

        mockMvc.perform(get("/api/submission-cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("API 테스트 사업"));
        mockMvc.perform(get("/api/submission-cases/{id}/requirements", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentName").value("사업자등록증"))
                .andExpect(jsonPath("$[0].sourceType").value("PMS_RFP_ITEM"));

        Instant modifiedAt = Instant.parse("2026-09-03T02:00:00Z");
        CompanyFileSearchPort.CompanyFileMetadata file = new CompanyFileSearchPort.CompanyFileMetadata(
                501L,
                UUID.randomUUID(),
                "사업자등록증_최신.pdf",
                "pdf",
                modifiedAt,
                modifiedAt
        );
        when(fileSearchPort.searchByKeywords(any(), anyInt())).thenReturn(List.of(file));
        when(fileSearchPort.findActiveFileById(501L)).thenReturn(Optional.of(file));

        mockMvc.perform(get(
                        "/api/submission-cases/{id}/requirements/{requirementId}/candidates",
                        caseId,
                        requirementId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileId").value(501))
                .andExpect(jsonPath("$[0].matchLevel").value("EXACT"))
                .andExpect(jsonPath("$[0].storagePath").doesNotExist());

        mockMvc.perform(put("/api/submission-cases/{id}/selections", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selections\":[{\"requirementId\":" + requirementId
                                + ",\"fileId\":501}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].originalFilename").value("사업자등록증_최신.pdf"));

        mockMvc.perform(get("/api/submission-cases/{id}/package", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionCase.id").value(caseId))
                .andExpect(jsonPath("$.requirements", hasSize(1)))
                .andExpect(jsonPath("$.selections", hasSize(1)));
    }

    @Test
    void returnsServiceUnavailableWithoutOptedInCompanyDatabase() throws Exception {
        when(projectQueryPort.findProjectById(999L))
                .thenThrow(new com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException());

        mockMvc.perform(post("/api/submission-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":999}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void searchesPmsProjectsWithOnlySelectionFields() throws Exception {
        when(projectQueryPort.searchProjects("고도화", 20)).thenReturn(List.of(
                new PmsProjectQueryPort.PmsProjectSummary(
                        301L, "공공정보시스템 고도화 사업", "테스트 발주기관", "20260903-01"
                )
        ));

        mockMvc.perform(get("/api/submission-projects").param("query", "고도화"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].projectId").value(301))
                .andExpect(jsonPath("$[0].projectName").value("공공정보시스템 고도화 사업"))
                .andExpect(jsonPath("$[0].organizationName").value("테스트 발주기관"))
                .andExpect(jsonPath("$[0].bidNoticeNo").value("20260903-01"))
                .andExpect(jsonPath("$[0].internalBizNo").doesNotExist())
                .andExpect(jsonPath("$[0].storagePath").doesNotExist());
    }

    @Test
    void rejectsBlankProjectSearchAndMapsUnavailableCompanyDatabase() throws Exception {
        mockMvc.perform(get("/api/submission-projects").param("query", " "))
                .andExpect(status().isBadRequest());

        when(projectQueryPort.searchProjects("검색", 20))
                .thenThrow(new com.comhu.bidmonitor.submission.service.CompanyDatabaseUnavailableException());
        mockMvc.perform(get("/api/submission-projects").param("query", "검색"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void createsManualChecklistCaseWithoutPmsProject() throws Exception {
        String response = mockMvc.perform(post("/api/submission-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirements":[
                                  {"category":"COMPANY_GENERAL","documentName":"사업자등록증","sourceReference":"BUSINESS_REGISTRATION"},
                                  {"category":"PERFORMANCE","documentName":"실적증명서","sourceReference":"PERFORMANCE"}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").isEmpty())
                .andExpect(jsonPath("$.projectName").value("직접 선택 제출서류"))
                .andReturn().getResponse().getContentAsString();

        Long caseId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.id")).longValue();
        mockMvc.perform(get("/api/submission-cases/{id}/requirements", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sourceType").value("USER_SELECTED"))
                .andExpect(jsonPath("$[1].performanceSelectionRequired").value(true));
    }
    @Autowired com.comhu.bidmonitor.submission.service.SubmissionCaseService localProjects;
    @Autowired org.springframework.jdbc.core.JdbcTemplate localJdbc;

    @Test
    void createsNamedEmptyProjectsAndListsThemWithoutPmsLookup() throws Exception {
        mockMvc.perform(post("/api/submission-cases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectName\":\"새 서류 프로젝트\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.projectName").value("새 서류 프로젝트"))
                .andExpect(jsonPath("$.projectId").isEmpty());
        var project = caseRepository.findAll().stream().filter(p -> p.getProjectName().equals("새 서류 프로젝트")).findFirst().orElseThrow();
        mockMvc.perform(get("/api/submission-cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + project.getId() + ")].total").value(org.hamcrest.Matchers.contains(0)));
        mockMvc.perform(get("/api/submission-cases/{id}/requirements", project.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(put("/api/submission-cases/{id}", project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectName\":\"수정한 프로젝트\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("수정한 프로젝트"));
        mockMvc.perform(post("/api/submission-cases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectName\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void requirementEditsKeepIdsAndSelectionsAndOnlyDeleteRemovedConnections() throws Exception {
        var first = localProjects.createProject("첫 프로젝트", null, List.of(
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                        com.comhu.bidmonitor.submission.domain.RequirementCategory.OTHER, "유지 서류", "KEEP"),
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                        com.comhu.bidmonitor.submission.domain.RequirementCategory.OTHER, "제거 서류", "REMOVE")));
        var second = localProjects.createProject("둘째 프로젝트", null, List.of(
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                        com.comhu.bidmonitor.submission.domain.RequirementCategory.OTHER, "유지 서류", "KEEP")));
        var old = requirementRepository.findBySubmissionCaseId(first.getId());
        when(fileSearchPort.findActiveFileById(101L)).thenReturn(Optional.of(
                new CompanyFileSearchPort.CompanyFileMetadata(101L, UUID.randomUUID(), "파일.pdf", "pdf", Instant.now(), Instant.now())));
        localProjects.replaceSelections(first.getId(), old.stream().map(r ->
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.SelectionChoice(r.getId(),101L)).toList());
        mockMvc.perform(put("/api/submission-cases/{id}/requirements",first.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"category\":\"OTHER\",\"documentName\":\"유지 서류\",\"sourceReference\":\"CHANGED\"},{\"category\":\"OTHER\",\"documentName\":\"새 서류\"}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(2)));
        var updated = requirementRepository.findBySubmissionCaseId(first.getId());
        org.assertj.core.api.Assertions.assertThat(updated.getFirst().getId()).isEqualTo(old.getFirst().getId());
        org.assertj.core.api.Assertions.assertThat(updated.getLast().getId()).isNotEqualTo(old.getLast().getId());
        org.assertj.core.api.Assertions.assertThat(localProjects.getPackage(first.getId()).getSelections()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(localProjects.getPackage(first.getId()).getSelections().getFirst().getRequirementId()).isEqualTo(old.getFirst().getId());
        org.assertj.core.api.Assertions.assertThat(requirementRepository.findBySubmissionCaseId(second.getId())).hasSize(1);
        mockMvc.perform(put("/api/submission-cases/{id}/requirements",first.getId()).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(0)));
        org.assertj.core.api.Assertions.assertThat(localProjects.getPackage(first.getId()).getSelections()).isEmpty();
    }

    @Test
    void performanceLinkIsPersistedValidatedAndLegacyInitializationNeverOverwritesServerChoice() throws Exception {
        var project = localProjects.createProject("실적 연결", null, List.of(
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                        com.comhu.bidmonitor.submission.domain.RequirementCategory.PERFORMANCE,"실적증명서","PERFORMANCE")));
        String p1 = UUID.randomUUID().toString(), p2 = UUID.randomUUID().toString();
        for (String id : List.of(p1,p2)) localJdbc.update(
                "INSERT INTO performance_project(id,name,deadline) VALUES(?,?,CURRENT_DATE)",id,"실적");
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceProjectId\":\"" + p1 + "\",\"initializePerformanceOnly\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceProjectId").value(p1));
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceProjectId\":\"" + p2 + "\",\"initializePerformanceOnly\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceProjectId").value(p1));
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceProjectId\":null}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceProjectId\":\"" + p2 + "\",\"initializePerformanceOnly\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceProjectId").isEmpty());
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceProjectId\":\"missing\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/submission-cases/{id}",project.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceLinkInitialized").value(true));
    }}
