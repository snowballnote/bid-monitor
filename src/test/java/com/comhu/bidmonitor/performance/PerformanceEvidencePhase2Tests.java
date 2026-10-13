package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:performance-evidence-phase2;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always", "company-db.enabled=false",
        "external-notice.scheduler.enabled=false",
        "performance.upload-directory=./target/performance-evidence-phase2-uploads"
})
class PerformanceEvidencePhase2Tests {
    @Autowired PerformanceService service;
    @Autowired PerformanceEvidenceRepository evidences;
    @Autowired PerformanceZipService zip;
    @Autowired PerformanceDriveFileRepository driveFiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean CompanyFileSearchPort companyFiles;
    @MockitoBean PerformanceFileContentPort content;
    @MockitoBean DriveEvidenceService drive;

    @Test
    void returnsAndMutatesMultipleEvidenceRowsWithoutTouchingOtherRows() {
        Entry entry = entry("Multiple");
        stubCompanyFile(11, "contract.pdf");
        stubCompanyFile(12, "tax.pdf");
        stubCompanyFile(13, "certificate.pdf");
        stubCompanyFile(14, "changed-tax.pdf");
        stubCompanyFile(15, "second-contract.pdf");

        service.addEvidence(entry.projectId(), entry.id(), input(11, EvidenceType.CONTRACT));
        service.addEvidence(entry.projectId(), entry.id(), input(12, EvidenceType.TAX_INVOICE));
        service.addEvidence(entry.projectId(), entry.id(), input(13, EvidenceType.CERTIFICATE));
        Entry all = service.addEvidence(entry.projectId(), entry.id(), input(15, EvidenceType.CONTRACT));

        assertThat(all.evidences()).hasSize(4);
        assertThat(all.evidences()).extracting(EvidenceView::evidenceType)
                .containsExactlyInAnyOrder(EvidenceType.CONTRACT, EvidenceType.CONTRACT,
                        EvidenceType.TAX_INVOICE, EvidenceType.CERTIFICATE);
        assertThat(all.evidences()).allMatch(e -> e.sourceType() == EvidenceSourceType.COMPANY_FILE);

        String taxId = all.evidences().stream().filter(e -> e.evidenceType() == EvidenceType.TAX_INVOICE)
                .findFirst().orElseThrow().evidenceId();
        Entry replaced = service.replaceEvidence(entry.projectId(), entry.id(), taxId,
                input(14, EvidenceType.TAX_INVOICE));
        assertThat(replaced.evidences()).hasSize(4);
        assertThat(replaced.evidences().stream().filter(e -> e.evidenceId().equals(taxId)).findFirst().orElseThrow().filename())
                .isEqualTo("changed-tax.pdf");
        assertThat(replaced.evidences()).extracting(EvidenceView::filename)
                .contains("contract.pdf", "second-contract.pdf", "certificate.pdf");

        Entry deleted = service.deleteEvidence(entry.projectId(), entry.id(), taxId);
        assertThat(deleted.evidences()).hasSize(3).noneMatch(e -> e.evidenceId().equals(taxId));
        assertThat(service.project(entry.projectId()).status()).isEqualTo("READY");
    }

    @Test
    void metadataAndKitcUpdatesKeepEvidenceAndLegacyColumnsReceiveNoNewWrites() {
        Entry entry = entry("Metadata");
        stubCompanyFile(21, "contract.pdf");
        Entry selected = service.addEvidence(entry.projectId(), entry.id(), input(21, EvidenceType.CONTRACT));
        String evidenceId = selected.evidences().getFirst().evidenceId();
        EntryInput old = selected.info();

        Entry updated = service.update(entry.projectId(), entry.id(), new EntryInput(
                old.pptNumber(), "Changed business", old.businessPeriod(), old.contractAmount(), old.client(),
                old.businessStatus(), old.selectedFileId(), old.evidenceType(), KitcStatus.REQUESTED,
                LocalDate.of(2026, 1, 1), null, old.selectedDriveFileId(), old.selectedUploadedFileId()));

        assertThat(updated.info().businessName()).isEqualTo("Changed business");
        assertThat(updated.info().kitcStatus()).isEqualTo(KitcStatus.REQUESTED);
        assertThat(updated.evidences()).singleElement().extracting(EvidenceView::evidenceId).isEqualTo(evidenceId);
        var legacy = jdbc.queryForMap("""
                SELECT selected_file_id, selected_drive_file_id, selected_uploaded_file_id,
                       selected_filename, selected_ext, evidence_type
                FROM performance_entry WHERE id = ?
                """, entry.id());
        assertThat(legacy.values()).containsOnlyNulls();
    }

    @Test
    void uploadedEvidenceIsEntryScopedDownloadableAndCanBeAddedMoreThanOnce() throws Exception {
        Entry first = entry("Upload owner");
        Entry second = service.paste(first.projectId(), new PasteInput(
                "2\tOther business\t2024.01 ~ 2025.12\t200\tOther client", null)).saved().getFirst();
        byte[] bytes = {1, 2, 3};
        Entry withUpload = service.uploadEvidence(first.projectId(), first.id(),
                new MockMultipartFile("file", "tax.pdf", "application/pdf", bytes), EvidenceType.TAX_INVOICE);
        EvidenceView upload = withUpload.evidences().getFirst();

        assertThatThrownBy(() -> service.addEvidence(second.projectId(), second.id(),
                new EvidenceInput(null, null, upload.selectedUploadedFileId(), EvidenceType.TAX_INVOICE)))
                .isInstanceOf(IllegalArgumentException.class);

        Entry twice = service.uploadEvidence(first.projectId(), first.id(),
                new MockMultipartFile("file", "tax-2.pdf", "application/pdf", new byte[]{4}), EvidenceType.TAX_INVOICE);
        assertThat(twice.evidences()).hasSize(2).allMatch(e -> e.sourceType() == EvidenceSourceType.DIRECT_UPLOAD);
        mvc.perform(get("/api/performance-projects/{project}/entries/{entry}/evidences/{evidence}/download",
                        first.projectId(), first.id(), upload.evidenceId()))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("tax.pdf")));
    }

    @Test
    void evidenceEndpointsSupportCompanyDriveUploadReplaceDeleteAndRejectMixedSources() throws Exception {
        Entry entry = entry("API");
        stubCompanyFile(31, "company.pdf");
        mvc.perform(post("/api/performance-projects/{project}/entries/{entry}/evidences", entry.projectId(), entry.id())
                        .contentType("application/json")
                        .content("{\"selectedFileId\":31,\"evidenceType\":\"CONTRACT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidences[0].sourceType").value("COMPANY_FILE"));

        var ref = driveFiles.register("test", "CNH",
                new FmsDrivePort.Item("drive.pdf", "/drive.pdf", false, 1, null));
        when(drive.selectable(any(Entry.class), eq(ref.id()), eq(EvidenceType.CERTIFICATE))).thenReturn(ref);
        mvc.perform(post("/api/performance-projects/{project}/entries/{entry}/evidences", entry.projectId(), entry.id())
                        .contentType("application/json")
                        .content("{\"selectedDriveFileId\":\"" + ref.id() + "\",\"evidenceType\":\"CERTIFICATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidences.length()").value(2));

        String companyEvidence = service.entries(entry.projectId()).getFirst().evidences().stream()
                .filter(e -> e.sourceType() == EvidenceSourceType.COMPANY_FILE).findFirst().orElseThrow().evidenceId();
        mvc.perform(multipart("/api/performance-projects/{project}/entries/{entry}/evidences/{evidence}/upload",
                        entry.projectId(), entry.id(), companyEvidence)
                        .file(new MockMultipartFile("file", "replacement.pdf", "application/pdf", new byte[]{9}))
                        .param("evidenceType", "TAX_INVOICE").with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidences.length()").value(2));
        mvc.perform(delete("/api/performance-projects/{project}/entries/{entry}/evidences/{evidence}",
                        entry.projectId(), entry.id(), companyEvidence))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidences.length()").value(1));

        mvc.perform(post("/api/performance-projects/{project}/entries/{entry}/evidences", entry.projectId(), entry.id())
                        .contentType("application/json")
                        .content("{\"selectedFileId\":31,\"selectedDriveFileId\":\"" + ref.id()
                                + "\",\"evidenceType\":\"CONTRACT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacySingleEvidenceApiWritesOnlyEvidenceTableAndReplacesOneEvidence() {
        Entry entry = entry("Legacy");
        stubCompanyFile(41, "first.pdf");
        stubCompanyFile(42, "replacement.pdf");
        EntryInput base = entry.info();
        Entry first = service.update(entry.projectId(), entry.id(), withSelection(base, 41, EvidenceType.CONTRACT));
        String evidenceId = first.evidences().getFirst().evidenceId();
        Entry replacement = service.update(entry.projectId(), entry.id(), withSelection(first.info(), 42, EvidenceType.TAX_INVOICE));

        assertThat(replacement.evidences()).singleElement().satisfies(e -> {
            assertThat(e.evidenceId()).isEqualTo(evidenceId);
            assertThat(e.evidenceType()).isEqualTo(EvidenceType.TAX_INVOICE);
            assertThat(e.filename()).isEqualTo("replacement.pdf");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_evidence WHERE entry_id=?", Integer.class, entry.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT selected_file_id FROM performance_entry WHERE id=?", Long.class, entry.id()))
                .isNull();
    }

    @Test
    void zipIncludesEveryEvidenceDisambiguatesNamesAndExcludesDeletedRows() throws Exception {
        Entry entry = entry("ZIP");
        for (long id = 51; id <= 54; id++) stubCompanyFile(id, "same.pdf");
        service.addEvidence(entry.projectId(), entry.id(), input(51, EvidenceType.CONTRACT));
        service.addEvidence(entry.projectId(), entry.id(), input(52, EvidenceType.TAX_INVOICE));
        Entry four = service.addEvidence(entry.projectId(), entry.id(), input(53, EvidenceType.TAX_INVOICE));
        four = service.addEvidence(entry.projectId(), entry.id(), input(54, EvidenceType.CERTIFICATE));
        when(content.open(anyLong())).thenAnswer(call ->
                new ByteArrayInputStream(new byte[]{call.getArgument(0, Long.class).byteValue()}));

        var zipped = zipEntries(zip.download(entry.projectId()));
        assertThat(zipped).hasSize(4);
        assertThat(new HashSet<>(zipped.stream().map(ZipItem::name).toList())).hasSize(4);
        assertThat(zipped).extracting(ZipItem::name).anyMatch(name -> name.contains("_2."));
        assertThat(zipped).extracting(item -> item.bytes()[0]).containsExactlyInAnyOrder((byte) 51, (byte) 52,
                (byte) 53, (byte) 54);
        assertThat(jdbc.queryForObject("SELECT selected_file_id FROM performance_entry WHERE id=?", Long.class,
                entry.id())).isNull();

        String deleted = four.evidences().stream().filter(e -> Long.valueOf(52).equals(e.selectedFileId()))
                .findFirst().orElseThrow().evidenceId();
        service.deleteEvidence(entry.projectId(), entry.id(), deleted);
        var afterDelete = zipEntries(zip.download(entry.projectId()));
        assertThat(afterDelete).hasSize(3);
        assertThat(afterDelete).extracting(item -> item.bytes()[0]).doesNotContain((byte) 52);
    }

    private Entry entry(String name) {
        String project = service.create(new ProjectInput(name, LocalDate.now().plusDays(1))).id();
        return service.paste(project, new PasteInput(
                "1\tBusiness " + name + "\t2024.01 ~ 2025.12\t100\tClient", null)).saved().getFirst();
    }

    private void stubCompanyFile(long id, String name) {
        when(companyFiles.findActiveFileById(id)).thenReturn(Optional.of(PerformanceWorkflowTests.file(id, name)));
    }

    private EvidenceInput input(long fileId, EvidenceType type) {
        return new EvidenceInput(fileId, null, null, type);
    }

    private EntryInput withSelection(EntryInput old, long fileId, EvidenceType type) {
        return new EntryInput(old.pptNumber(), old.businessName(), old.businessPeriod(), old.contractAmount(),
                old.client(), old.businessStatus(), fileId, type, old.kitcStatus(), old.requestedAt(),
                old.repliedAt(), null, null);
    }

    private ArrayList<ZipItem> zipEntries(byte[] bytes) throws Exception {
        var result = new ArrayList<ZipItem>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry item;
            while ((item = input.getNextEntry()) != null) result.add(new ZipItem(item.getName(), input.readAllBytes()));
        }
        return result;
    }

    private record ZipItem(String name, byte[] bytes) { }
}
