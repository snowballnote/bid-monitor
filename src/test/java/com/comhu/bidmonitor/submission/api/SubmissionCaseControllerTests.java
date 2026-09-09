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
                        .content("{\"projectId\":301,\"projectName\":\"API 테스트 사업\",\"deadline\":\"2026-12-31\"}"))
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
                        .content("{\"projectName\":\"PMS 연결 테스트\",\"deadline\":\"2026-12-31\",\"projectId\":999}"))
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
                                {"projectName":"직접 선택 제출서류","deadline":"2026-12-31","requirements":[
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
                .content("{\"projectName\":\"새 서류 프로젝트\",\"deadline\":\"2026-12-31\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.projectName").value("새 서류 프로젝트"))
                .andExpect(jsonPath("$.projectId").isEmpty());
        var project = caseRepository.findAll().stream().filter(p -> p.getProjectName().equals("새 서류 프로젝트")).findFirst().orElseThrow();
        mockMvc.perform(get("/api/submission-cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + project.getId() + ")].total").value(org.hamcrest.Matchers.contains(0)));
        mockMvc.perform(get("/api/submission-cases/{id}/requirements", project.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(put("/api/submission-cases/{id}", project.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectName\":\"수정한 프로젝트\",\"deadline\":\"2027-01-15\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("수정한 프로젝트"))
                .andExpect(jsonPath("$.deadline").value("2027-01-15"));
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
    }    @Test
    void newProjectRequiresNameAndDeadlineAndListReturnsDeadline() throws Exception {
        mockMvc.perform(post("/api/submission-cases").contentType(MediaType.APPLICATION_JSON).content("""
                {"projectName":"마감일 누락"}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/submission-cases").contentType(MediaType.APPLICATION_JSON).content("""
                {"deadline":"2026-12-31"}
                """)).andExpect(status().isBadRequest());
        var project = localProjects.createProject("마감일 프로젝트",null,List.of(),java.time.LocalDate.of(2026,12,31));
        mockMvc.perform(get("/api/submission-cases/{id}",project.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").value("2026-12-31"));
        mockMvc.perform(get("/api/submission-cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + project.getId() + ")].deadline").value(org.hamcrest.Matchers.contains("2026-12-31")));
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON).content("""
                {"deadline":null}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/submission-cases/{id}",project.getId()).contentType(MediaType.APPLICATION_JSON).content("""
                {"deadline":"2026-02-30"}
                """)).andExpect(status().isBadRequest());
    }

    @Test
    void deletingProjectCascadesOnlyItsRequirementsAndSelectionsAndKeepsPerformanceProject() throws Exception {
        var requirement = new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                com.comhu.bidmonitor.submission.domain.RequirementCategory.PERFORMANCE,"실적증명서","PERFORMANCE");
        var first = localProjects.createProject("삭제 대상",null,List.of(requirement),java.time.LocalDate.now());
        var second = localProjects.createProject("보존 대상",null,List.of(requirement),java.time.LocalDate.now());
        String performance = UUID.randomUUID().toString();
        localJdbc.update("INSERT INTO performance_project(id,name,deadline) VALUES(?,?,CURRENT_DATE)",performance,"실적 보존");
        localProjects.updateProject(first.getId(),null,true,performance,false);
        var firstRequirement = requirementRepository.findBySubmissionCaseId(first.getId()).getFirst();
        var secondRequirement = requirementRepository.findBySubmissionCaseId(second.getId()).getFirst();
        for (var r : List.of(firstRequirement,secondRequirement)) localJdbc.update("""
                INSERT INTO submission_document_selection(submission_case_id,requirement_id,file_id,file_public_id,original_filename,selected_at)
                VALUES(?,?,1,?,'보존.pdf',CURRENT_TIMESTAMP)
                """,r.getSubmissionCaseId(),r.getId(),UUID.randomUUID().toString());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/submission-cases/{id}",first.getId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/submission-cases/{id}",first.getId())).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(requirementRepository.findBySubmissionCaseId(first.getId())).isEmpty();
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT count(*) FROM submission_document_selection WHERE submission_case_id=?",Long.class,first.getId())).isZero();
        org.assertj.core.api.Assertions.assertThat(localProjects.getPackage(second.getId()).getSelections()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT count(*) FROM performance_project WHERE id=?",Long.class,performance)).isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/submission-cases/{id}",first.getId()))
                .andExpect(status().isNotFound());
    }
    @Test
    void masterCrudDoesNotMutateProjectSnapshotsOrFileLinks() throws Exception {
        var project=localProjects.createProject("마스터 독립",null,List.of(
                new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                    com.comhu.bidmonitor.submission.domain.RequirementCategory.COMPANY_GENERAL,"사업자등록증","BUSINESS_REGISTRATION")), java.time.LocalDate.of(2026,12,31));
        var requirement=requirementRepository.findBySubmissionCaseId(project.getId()).getFirst();
        localJdbc.update("INSERT INTO submission_document_selection(submission_case_id,requirement_id,file_id,file_public_id,original_filename,file_ext,selected_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                project.getId(),requirement.getId(),501L,UUID.randomUUID().toString(),"보존.pdf","pdf");
        mockMvc.perform(post("/api/submission-document-masters").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"새 서류","category":"OTHER"}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.category").value("OTHER"));
        mockMvc.perform(put("/api/submission-document-masters/BUSINESS_REGISTRATION").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"변경한 서류명","category":"PERSONNEL"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.requirementCategory").value("PERSONNEL"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/submission-document-masters/BUSINESS_REGISTRATION"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/submission-document-masters"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 'BUSINESS_REGISTRATION')]").isEmpty());
        mockMvc.perform(post("/api/submission-document-masters").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" ","category":"OTHER"}
                """)).andExpect(status().isBadRequest());
        String schema=new org.springframework.core.io.ClassPathResource("schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String catalogSchema=schema.substring(schema.indexOf("INSERT INTO submission_document_master"));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(catalogSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .execute(localJdbc.getDataSource());
        mockMvc.perform(get("/api/submission-document-masters")).andExpect(jsonPath("$[?(@.id == 'BUSINESS_REGISTRATION')]").isEmpty());
        // Saving the project's unchanged checklist still retains the same requirement and selection.
        localProjects.replaceRequirements(project.getId(),List.of(new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                requirement.getCategory(),requirement.getDocumentName(),requirement.getSourceReference())));
        org.assertj.core.api.Assertions.assertThat(requirementRepository.findBySubmissionCaseId(project.getId()).getFirst().getId()).isEqualTo(requirement.getId());
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT COUNT(*) FROM submission_document_selection WHERE requirement_id=?",Long.class,requirement.getId())).isEqualTo(1);
    }

    @Test
    void performanceAutoLinkReusesConnectionAndRejectsMissingDeadline() throws Exception {
        var project=localProjects.createProject("자동 실적 연결",null,List.of(),java.time.LocalDate.of(2026,12,31));
        long before=localJdbc.queryForObject("SELECT COUNT(*) FROM performance_project",Long.class);
        mockMvc.perform(post("/api/submission-cases/{id}/performance-project",project.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.performanceProjectId").isNotEmpty());
        String linked=caseRepository.findById(project.getId()).orElseThrow().getPerformanceProjectId();
        mockMvc.perform(post("/api/submission-cases/{id}/performance-project",project.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.performanceProjectId").value(linked));
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT COUNT(*) FROM performance_project",Long.class)).isEqualTo(before+1);
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT name FROM performance_project WHERE id=?",String.class,linked)).isEqualTo("자동 실적 연결");
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT deadline FROM performance_project WHERE id=?",java.time.LocalDate.class,linked)).isEqualTo(project.getDeadline());
        var legacy=localProjects.createProject("마감일 없는 기존 프로젝트",null,List.of());
        mockMvc.perform(post("/api/submission-cases/{id}/performance-project",legacy.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void renamedPerformanceMasterRetainsSpecialAction() throws Exception {
        mockMvc.perform(put("/api/submission-document-masters/PERFORMANCE").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"실적 증빙","category":"PERFORMANCE"}
                """)).andExpect(status().isOk());
        var project=localProjects.createProject("실적 이름 변경",null,List.of(new com.comhu.bidmonitor.submission.service.SubmissionCaseService.ManualRequirement(
                com.comhu.bidmonitor.submission.domain.RequirementCategory.PERFORMANCE,"실적 증빙","PERFORMANCE")),java.time.LocalDate.of(2026,12,31));
        mockMvc.perform(get("/api/submission-cases/{id}/requirements",project.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].performanceSelectionRequired").value(true));
    }

    @Autowired com.comhu.bidmonitor.submission.service.SubmissionPerformanceLinkService performanceLinks;
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentPerformanceClicksCreateOneProject() throws Exception {
        var project=localProjects.createProject("동시 연결 테스트",null,List.of(),java.time.LocalDate.of(2026,12,31));
        var gate=new java.util.concurrent.CountDownLatch(1);
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{gate.await();return performanceLinks.ensure(project.getId()).getPerformanceProjectId();});
            var second=executor.submit(()->{gate.await();return performanceLinks.ensure(project.getId()).getPerformanceProjectId();});
            gate.countDown();
            String id=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(second.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);
            org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT COUNT(*) FROM performance_project WHERE name=?",Long.class,"동시 연결 테스트")).isEqualTo(1);
        } finally {
            localProjects.deleteProject(project.getId());
            localJdbc.update("DELETE FROM performance_project WHERE name=?","동시 연결 테스트");
        }
    }

    @Autowired com.comhu.bidmonitor.submission.service.SubmissionDocumentMasterService masters;
    @Test
    void registeredCommonFileIsCollectedWithoutCompanySearchAndReplacementIsExplicit() throws Exception {
        var master=masters.create(new com.comhu.bidmonitor.submission.service.SubmissionDocumentMasterService.Input("추가 공통서류",
                com.comhu.bidmonitor.submission.service.SubmissionDocumentMasterService.Category.COMPANY_COMMON));
        var firstFile=new CompanyFileSearchPort.CompanyFileMetadata(801L,UUID.randomUUID(),"공통1.pdf","pdf",Instant.now(),Instant.now());
        var nextFile=new CompanyFileSearchPort.CompanyFileMetadata(802L,UUID.randomUUID(),"공통2.pdf","pdf",Instant.now(),Instant.now());
        when(fileSearchPort.findActiveFileById(801L)).thenReturn(Optional.of(firstFile));
        when(fileSearchPort.findActiveFileById(802L)).thenReturn(Optional.of(nextFile));
        mockMvc.perform(put("/api/submission-document-masters/{id}/current-file",master.id()).contentType(MediaType.APPLICATION_JSON).content("""
                {"fileId":801}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.originalFilename").value("공통1.pdf"))
                .andExpect(jsonPath("$.storagePath").doesNotExist());
        // Existing enum-based common-document endpoint remains compatible with custom master types.
        mockMvc.perform(get("/api/submission-common-documents")).andExpect(status().isOk());
        var a=localProjects.createProject("공통 연결 A",null,List.of(),java.time.LocalDate.of(2026,12,31));
        var b=localProjects.createProject("공통 연결 B",null,List.of(),java.time.LocalDate.of(2026,12,31));
        String body="""
                [{"category":"COMPANY_GENERAL","documentName":"추가 공통서류","sourceReference":"%s"}]
                """.formatted(master.sourceReference());
        org.mockito.Mockito.clearInvocations(fileSearchPort);
        for(var project:List.of(a,b))mockMvc.perform(post("/api/submission-cases/{id}/collect",project.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missingRequirementIds").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(fileSearchPort);
        var requirement=requirementRepository.findBySubmissionCaseId(a.getId()).getFirst();
        long selection=localJdbc.queryForObject("SELECT id FROM submission_document_selection WHERE requirement_id=?",Long.class,requirement.getId());
        mockMvc.perform(post("/api/submission-cases/{id}/collect",a.getId()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT id FROM submission_document_selection WHERE requirement_id=?",Long.class,requirement.getId())).isEqualTo(selection);
        mockMvc.perform(put("/api/submission-document-masters/{id}/current-file",master.id()).contentType(MediaType.APPLICATION_JSON).content("""
                {"fileId":802}
                """)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT file_id FROM submission_document_selection WHERE requirement_id=?",Long.class,requirement.getId())).isEqualTo(801);
        mockMvc.perform(post("/api/submission-cases/{id}/collect",a.getId()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT file_id FROM submission_document_selection WHERE requirement_id=?",Long.class,requirement.getId())).isEqualTo(802);
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT file_id FROM submission_document_selection WHERE submission_case_id=?",Long.class,b.getId())).isEqualTo(801);
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT COUNT(*) FROM submission_common_document WHERE document_type=?",Long.class,master.sourceReference())).isEqualTo(1);
    }
    @Test
    void commonFileMissingIsExplicitAndInvalidReplacementPreservesCurrent() throws Exception {
        var project=localProjects.createProject("공통 미등록",null,List.of(),java.time.LocalDate.of(2026,12,31));
        String body="""
                [{"category":"COMPANY_GENERAL","documentName":"사업자등록증","sourceReference":"BUSINESS_REGISTRATION"}]
                """;
        mockMvc.perform(post("/api/submission-cases/{id}/collect",project.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missingRequirementIds",hasSize(1)));
        mockMvc.perform(get("/api/submission-cases/{id}/requirements",project.getId())).andExpect(jsonPath("$[0].companyCommon").value(true));
        mockMvc.perform(get("/api/submission-cases/{id}/package",project.getId())).andExpect(jsonPath("$.selections").isEmpty());
        when(fileSearchPort.findActiveFileById(899L)).thenReturn(Optional.empty());
        mockMvc.perform(put("/api/submission-document-masters/BUSINESS_REGISTRATION/current-file").contentType(MediaType.APPLICATION_JSON).content("""
                {"fileId":899}
                """)).andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(localJdbc.queryForObject("SELECT file_id FROM submission_common_document WHERE document_type='BUSINESS_REGISTRATION'",Long.class)).isNull();
    }
}
