package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:performance-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "company-db.enabled=false", "external-notice.scheduler.enabled=false"
})
class PerformanceControllerTests {
    @Autowired MockMvc mvc;
    @Autowired PerformanceService service;
    @Autowired PerformanceDriveFileRepository registry;
    @MockitoBean CompanyFileSearchPort files;
    @MockitoBean PerformanceFileContentPort content;
    @MockitoBean DriveEvidenceService drive;

    @Test
    void createsProjectAndImportsPartialRowsWithoutLeakingInternalFields() throws Exception {
        mvc.perform(post("/api/performance-projects").contentType("application/json")
                .content("{\"name\":\"프로젝트\",\"deadline\":\"2026-12-31\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("프로젝트"));
        var project = service.create(new ProjectInput("API", LocalDate.now())).id();
        mvc.perform(post("/api/performance-projects/" + project + "/import").contentType("application/json")
                .content("{\"text\":\"007\\t사업A\\t2024.01 ~ 2025.12\\t100\\t발주처\\n8\\t사업B\\t오류\\t100\\t발주처\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved.length()").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.saved[0].info.pptNumber").value("007"))
                .andExpect(jsonPath("$.saved[0].info.selectedFileId").isEmpty());
        mvc.perform(get("/api/performance-projects/" + project + "/entries"))
                .andExpect(status().isOk()).andExpect(content().string(allOf(
                        not(containsString("storage_path")), not(containsString("storagePath")),
                        not(containsString("credential")), not(containsString("password")))));
    }

    @Test
    void candidatesAndDatabaseErrorsHaveOnlySafeResponses() throws Exception {
        var entry = entry();
        when(drive.recommend(any())).thenReturn(new Recommendations(List.of(new Candidate(
                new EvidenceFile(null, "fms-opaque-id", "사업A 발주처 실적증명원.pdf", "pdf", 10, null),
                EvidenceType.CERTIFICATE, "사업명 일치")), "후보 확인"));
        String url = "/api/performance-projects/" + entry.projectId() + "/entries/" + entry.id() + "/candidates";
        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].file.driveFileId").value("fms-opaque-id"))
                .andExpect(jsonPath("$.candidates[0].file.storagePath").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].file.path").doesNotExist());
        when(drive.recommend(any()))
                .thenThrow(new DataAccessResourceFailureException("storage_path=/secret credential=password"));
        mvc.perform(get(url)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void updatePersistsManualFileAndUserDatesAndDownloadReturnsZip() throws Exception {
        var entry = entry();
        when(files.findActiveFileById(7L)).thenReturn(java.util.Optional.of(PerformanceWorkflowTests.file(7, "파일.pdf")));
        mvc.perform(put("/api/performance-projects/" + entry.projectId() + "/entries/" + entry.id())
                .contentType("application/json").content("""
                {"pptNumber":"01","businessName":"수정 사업","businessPeriod":"2024.01 ~ 2025.12",
                "contractAmount":"200","client":"발주처","selectedFileId":"7","evidenceType":"CONTRACT",
                "kitcStatus":"RECEIVED","requestedAt":"2026-01-01","repliedAt":"2026-01-02"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.info.businessName").value("수정 사업"))
                .andExpect(jsonPath("$.info.repliedAt").value("2026-01-02"))
                .andExpect(jsonPath("$.selectedFilename").value("파일.pdf"));
        when(content.open(7L)).thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        mvc.perform(get("/api/performance-projects/" + entry.projectId() + "/download"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
        when(content.open(7L)).thenThrow(new IOException("/secret credential=password"));
        mvc.perform(get("/api/performance-projects/" + entry.projectId() + "/download"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void rejectsMissingProjectMalformedDatesAndInvalidKitcState() throws Exception {
        mvc.perform(get("/api/performance-projects/missing/entries")).andExpect(status().isNotFound());
        mvc.perform(post("/api/performance-projects").contentType("application/json")
                .content("{\"name\":\"API\",\"deadline\":\"bad-date\"}")).andExpect(status().isBadRequest());
        var entry = entry();
        mvc.perform(put("/api/performance-projects/" + entry.projectId() + "/entries/" + entry.id())
                .contentType("application/json").content("""
                {"pptNumber":"1","businessName":"사업A","businessPeriod":"2024.01 ~ 2025.12",
                "contractAmount":"100","client":"발주처","kitcStatus":"REQUESTED"}
                """)).andExpect(status().isBadRequest());
    }

    @Test
    void servesPageWithOnlyThreeProjectListColumnsAndLinksFromSubmissionPage() throws Exception {
        mvc.perform(get("/performances/index.html")).andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                        .contains("<th>프로젝트명</th><th>D-day</th><th>상태</th>"));
        mvc.perform(get("/submissions/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/performances/index.html")));
    }

    @Test
    void drivePermissionFailureIs403WithoutAnEmptyZip() throws Exception {
        var entry = entry();
        // Persist the referenced ID only into this test's primary H2 through the real registry.
        var item = new FmsDrivePort.Item("실적증명원.pdf", "/private/실적증명원.pdf", false, 10, null);
        var ref = registry.register("internal-origin", "CNH", item);
        when(drive.selectable(eq(ref.id()), eq(EvidenceType.CERTIFICATE))).thenReturn(ref);
        var info = entry.info();
        service.update(entry.projectId(), entry.id(), new EntryInput(info.pptNumber(), info.businessName(),
                info.businessPeriod(), info.contractAmount(), info.client(), null, null,
                EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, ref.id()));
        when(drive.open(ref.id())).thenThrow(new FmsDriveException("선택한 FMS 파일의 다운로드 권한이 없습니다.", true));
        mvc.perform(get("/api/performance-projects/" + entry.projectId() + "/download"))
                .andExpect(status().isForbidden()).andExpect(content().string(not(containsString("/private"))));
        mvc.perform(get("/api/performance-projects/" + entry.projectId() + "/entries"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].info.selectedDriveFileId").value(ref.id()))
                .andExpect(content().string(not(containsString("/private"))));
    }
    private Entry entry() {
        var project = service.create(new ProjectInput("API", LocalDate.now())).id();
        return service.paste(project, new PasteInput("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처", null)).saved().getFirst();
    }
}