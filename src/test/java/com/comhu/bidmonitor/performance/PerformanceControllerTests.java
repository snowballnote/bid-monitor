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
        "company-db.enabled=false", "external-notice.scheduler.enabled=false",
        "performance.upload-directory=./target/performance-test-uploads"
})
class PerformanceControllerTests {
    @Autowired MockMvc mvc;
    @Autowired PerformanceService service;
    @Autowired PerformanceDriveFileRepository registry;
    @MockitoBean CompanyFileSearchPort files;
    @MockitoBean PerformanceFileContentPort content;
    @MockitoBean DriveEvidenceService drive;

    @Test
    void uploadsOngoingContractReplacesAndUnlinksWithoutFmsAccess() throws Exception {
        var project = service.create(new ProjectInput("Upload", LocalDate.now())).id();
        var entry = service.paste(project, new PasteInput("1\t사업\t2026.01 ~ 수행중\t100\t기관", null)).saved().getFirst();
        String url = "/api/performance-projects/" + project + "/entries/" + entry.id();
        mvc.perform(multipart(url + "/upload").file(new org.springframework.mock.web.MockMultipartFile(
                "file", "certificate.pdf", "application/pdf", new byte[]{1}))
                .param("evidenceType", "CERTIFICATE")).andExpect(status().isBadRequest());
        mvc.perform(multipart(url + "/upload").file(new org.springframework.mock.web.MockMultipartFile(
                "file", "C:\\fakepath\\contract.pdf", "application/pdf", new byte[]{1, 2, 3}))
                .param("evidenceType", "CONTRACT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedFilename").value("contract.pdf"))
                .andExpect(jsonPath("$.info.selectedUploadedFileId").isNotEmpty())
                .andExpect(jsonPath("$.info.selectedDriveFileId").isEmpty())
                .andExpect(jsonPath("$.info.selectedFileId").isEmpty())
                .andExpect(jsonPath("$.info.evidenceType").value("CONTRACT"));
        String first = service.entries(project).getFirst().info().selectedUploadedFileId();
        org.junit.jupiter.api.Assertions.assertEquals("READY", service.project(project).status());
        mvc.perform(multipart(url + "/upload").file(new org.springframework.mock.web.MockMultipartFile(
                "file", "replacement.pdf", "application/pdf", new byte[]{4, 5}))
                .param("evidenceType", "CONTRACT")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNotEquals(first, service.entries(project).getFirst().info().selectedUploadedFileId());
        var download = mvc.perform(get("/api/performance-projects/" + project + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(download))) {
            org.junit.jupiter.api.Assertions.assertTrue(zip.getNextEntry().getName().contains("계약서"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{4, 5}, zip.readAllBytes());
            org.junit.jupiter.api.Assertions.assertNull(zip.getNextEntry());
        }
        mvc.perform(put(url).contentType("application/json").content("""
                {"pptNumber":"1","businessName":"사업","businessPeriod":"2026.01 ~ 수행중",
                 "contractAmount":"100","client":"기관","kitcStatus":"NEEDED"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.info.selectedUploadedFileId").isEmpty())
                .andExpect(jsonPath("$.selectedFilename").isEmpty());
        verifyNoInteractions(files, drive, content);
    }

    @Test
    void uploadedAndFmsFilesShareZipAndUploadedIdsAreEntryScoped() throws Exception {
        var first = entry();
        var second = service.paste(first.projectId(), new PasteInput("2\t사업B\t2024.01 ~ 2025.12\t200\t기관", null)).saved().getFirst();
        service.upload(first.projectId(), first.id(), new org.springframework.mock.web.MockMultipartFile(
                "file", "local.pdf", "application/pdf", new byte[]{9}), EvidenceType.CERTIFICATE);
        var uploaded = service.entries(first.projectId()).stream().filter(e -> e.id().equals(first.id())).findFirst().orElseThrow();
        var i = second.info();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(second.projectId(), second.id(),
                new EntryInput(i.pptNumber(), i.businessName(), i.businessPeriod(), i.contractAmount(), i.client(), null,
                        null, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, null, uploaded.info().selectedUploadedFileId())));
        var ref = registry.register("test-origin", "CNH", new FmsDrivePort.Item("fms.pdf", "/test/fms.pdf", false, 1, null));
        when(drive.selectable(org.mockito.Mockito.any(Entry.class), eq(ref.id()), eq(EvidenceType.CERTIFICATE))).thenReturn(ref);
        service.update(second.projectId(), second.id(), new EntryInput(i.pptNumber(), i.businessName(), i.businessPeriod(),
                i.contractAmount(), i.client(), null, null, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, ref.id()));
        when(drive.open(ref.id())).thenReturn(new java.io.ByteArrayInputStream(new byte[]{8}));
        byte[] bytes = mvc.perform(get("/api/performance-projects/" + first.projectId() + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var payloads = new java.util.HashSet<Integer>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) payloads.add((int) zip.readAllBytes()[0]);
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(8, 9), payloads);
        verify(drive).open(ref.id());verifyNoInteractions(files, content);
    }

    @Test
    void invalidUploadsPreserveExistingSelection() throws Exception {
        var entry = entry();
        var selected = service.upload(entry.projectId(), entry.id(), new org.springframework.mock.web.MockMultipartFile(
                "file", "valid.pdf", "application/pdf", new byte[]{1}), EvidenceType.CONTRACT);
        String url = "/api/performance-projects/" + entry.projectId() + "/entries/" + entry.id() + "/upload";
        mvc.perform(multipart(url).file(new org.springframework.mock.web.MockMultipartFile("file", new byte[0]))
                .param("evidenceType", "CONTRACT")).andExpect(status().isBadRequest());
        mvc.perform(multipart(url).file(new org.springframework.mock.web.MockMultipartFile("file", "large.pdf", "application/pdf",
                new byte[(int) PerformanceUploadStore.MAX_BYTES + 1])).param("evidenceType", "CONTRACT"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(selected.info().selectedUploadedFileId(), service.entries(entry.projectId()).getFirst().info().selectedUploadedFileId());
        verifyNoInteractions(files, drive, content);
    }

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
        mvc.perform(get("/submissions/submissions.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/performances/index.html")));
    }

    @Test
    void drivePermissionFailureIs403WithoutAnEmptyZip() throws Exception {
        var entry = entry();
        // Persist the referenced ID only into this test's primary H2 through the real registry.
        var item = new FmsDrivePort.Item("실적증명원.pdf", "/private/실적증명원.pdf", false, 10, null);
        var ref = registry.register("internal-origin", "CNH", item);
        when(drive.selectable(org.mockito.Mockito.any(Entry.class), eq(ref.id()), eq(EvidenceType.CERTIFICATE))).thenReturn(ref);
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
