package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort.CompanyFileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceWorkflowTests {
    private PerformanceRepository repository;
    private PerformanceService service;
    private CompanyFileSearchPort files;
    private DriveEvidenceService drive;
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;
    private String project;
    private final PerformanceTableParser parser = new PerformanceTableParser();

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new PerformanceRepository(jdbc);
        files = mock(CompanyFileSearchPort.class);
        drive = mock(DriveEvidenceService.class);
        service = new PerformanceService(repository, parser, files, drive);
        project = service.create(new ProjectInput("제안 프로젝트", LocalDate.now().plusDays(3))).id();
    }

    @Test
    void schemaCanRerunWithoutLosingExistingSelectionsOrDuplicatingCommonTypes() {
        var entry = importRows("007\t사업A\t2024.01 ~ 2025.12\t1,000만원\t발주처").saved().getFirst();
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "증빙.pdf")));
        service.update(project, entry.id(), input(entry.info(), 7L, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null));
        jdbc.update("UPDATE submission_common_document SET issued_at = DATE '2025-01-01' WHERE document_type = 'BUSINESS_REGISTRATION'");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        assertThat(repository.entry(project, entry.id()).info().selectedFileId()).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submission_common_document", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT issued_at FROM submission_common_document WHERE document_type = 'BUSINESS_REGISTRATION'",
                LocalDate.class)).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO submission_common_document
                SELECT * FROM submission_common_document WHERE document_type = 'BUSINESS_REGISTRATION'
                """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void htmlPreservesCellNewlinesAndOriginalPptNumberAndSkipsHeader() {
        var result = service.paste(project, new PasteInput("", """
                <table><tr><th>번호</th><th>사업명</th><th>사업기간</th><th>계약금액</th><th>발주처</th></tr>
                <tr><td>007</td><td>통합<br>시스템 사업</td><td>2024.01<br>~ 2025.12</td><td>1,000만원</td><td>발주처</td></tr></table>
                """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.saved()).singleElement().satisfies(e -> {
            assertThat(e.info().pptNumber()).isEqualTo("007");
            assertThat(e.info().businessName()).isEqualTo("통합 시스템 사업");
            assertThat(e.info().contractAmount()).isEqualTo("1,000만원");
            assertThat(e.info().selectedFileId()).isNull();
        });
        verifyNoInteractions(files);
    }

    @Test
    void parsesQuotedMultilineTsvAndKeepsValidRowsAroundBadRows() {
        var result = importRows("번호\t사업명\t사업기간\t계약금액\t발주처\n"
                + "01\t\"통합\n사업\"\t2024.01 ~ 2025.12\t100\t발주처\n"
                + "02\t불량\t날짜 오류\t100\t발주처\n"
                + "03\t사업B\t2024.01 ~ 수행중\t200\t발주처");
        assertThat(result.saved()).extracting(e -> e.info().pptNumber()).containsExactly("01", "03");
        assertThat(result.saved().getFirst().info().businessName()).isEqualTo("통합 사업");
        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.row()).isEqualTo(3));
    }

    @Test
    void duplicateNumberIsPerProjectAndDoesNotBlockOtherRows() {
        var result = importRows("5\t사업A\t2024.01 ~ 2025.12\t100\t발주처\n"
                + "5\t사업B\t2024.01 ~ 2025.12\t100\t발주처\n"
                + "9\t사업C\t2024.01 ~ 2025.12\t100\t발주처");
        assertThat(result.saved()).hasSize(2);
        assertThat(result.errors()).hasSize(1);
        var other = service.create(new ProjectInput("다른 프로젝트", LocalDate.now()));
        assertThat(service.paste(other.id(), new PasteInput("5\t사업A\t2024.01 ~ 2025.12\t100\t발주처", null)).saved()).hasSize(1);
    }

    @Test
    void driveRecommendationsDoNotSelectFilesOrUseCompanySearch() {
        var entry = importRows("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처").saved().getFirst();
        when(drive.recommend(any())).thenReturn(new Recommendations(List.of(
                new Candidate(new EvidenceFile(null, UUID.randomUUID().toString(), "실적증명원.pdf", "pdf", 20, null),
                        EvidenceType.CERTIFICATE, "사업명 일치")), "후보 확인"));
        assertThat(service.candidates(project, entry.id()).candidates()).extracting(Candidate::evidenceType)
                .containsExactly(EvidenceType.CERTIFICATE);
        assertThat(repository.entry(project, entry.id()).info().selectedFileId()).isNull();
        assertThat(repository.entry(project, entry.id()).info().selectedDriveFileId()).isNull();
        verifyNoInteractions(files);
    }
    @Test
    void ongoingExcludesCertificateAndFallsBackToKitc() {
        var entry = importRows("1\t사업A\t2024.01 ~ 수행중\t100\t발주처").saved().getFirst();
        when(drive.recommend(any())).thenReturn(new Recommendations(List.of(), "KITC 요청 필요"));
        var result = service.candidates(project, entry.id());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.nextAction()).isEqualTo("KITC 요청 필요");
        assertThatThrownBy(() -> service.update(project, entry.id(),
                input(entry.info(), 1L, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesDatesAndRestoresUserEnteredKitcHistoryAndFileAfterRepositoryRecreation() {
        var entry = importRows("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처").saved().getFirst();
        LocalDate request = LocalDate.of(2026, 1, 2);
        assertThatThrownBy(() -> service.update(project, entry.id(),
                input(entry.info(), null, null, KitcStatus.REQUESTED, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(project, entry.id(),
                input(entry.info(), null, null, KitcStatus.RECEIVED, request, request.minusDays(1)))).isInstanceOf(IllegalArgumentException.class);
        service.update(project, entry.id(), input(entry.info(), null, null, KitcStatus.REQUESTED, request, null));
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "직접연결.pdf")));
        service.update(project, entry.id(), input(entry.info(), 7L, EvidenceType.CONTRACT, KitcStatus.RECEIVED, request, request.plusDays(1)));
        var restored = new PerformanceRepository(jdbc).entry(project, entry.id());
        assertThat(restored.info().requestedAt()).isEqualTo(request);
        assertThat(restored.info().repliedAt()).isEqualTo(request.plusDays(1));
        assertThat(restored.info().kitcStatus()).isEqualTo(KitcStatus.RECEIVED);
        assertThat(restored.selectedFilename()).isEqualTo("직접연결.pdf");
        assertThat(service.project(project).status()).isEqualTo("READY");
    }

    @Test
    void sameFileCanBeSelectedForMultipleEntriesAndZipContainsBothNamedCopies() throws Exception {
        var entries = importRows("007\t사업A\t2024.01 ~ 2025.12\t100\t발주처\n008\t사업B\t2024.01 ~ 2025.12\t100\t발주처").saved();
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "원본.pdf")));
        for (var entry : entries) service.update(project, entry.id(),
                input(entry.info(), 7L, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null));
        var content = mock(PerformanceFileContentPort.class);
        when(content.open(7L)).thenAnswer(invocation -> new ByteArrayInputStream("contents".getBytes(StandardCharsets.UTF_8)));
        byte[] archive = new PerformanceZipService(repository, content, drive).download(project);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("007_실적증명서_(발주처) 사업A.pdf");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("contents");
            assertThat(zip.getNextEntry().getName()).isEqualTo("008_실적증명서_(발주처) 사업B.pdf");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("contents");
            assertThat(zip.getNextEntry()).isNull();
        }
        verify(content, times(2)).open(7L);
    }

    @Test
    void zipRejectsEmptySelectionAndMissingFileWithoutReturningPartialZip() throws Exception {
        var content = mock(PerformanceFileContentPort.class);
        var zip = new PerformanceZipService(repository, content, drive);
        assertThatThrownBy(() -> zip.download(project)).isInstanceOf(IllegalArgumentException.class);
        var entry = importRows("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처").saved().getFirst();
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "원본.pdf")));
        service.update(project, entry.id(), input(entry.info(), 7L, EvidenceType.CONTRACT, KitcStatus.NEEDED, null, null));
        when(content.open(7L)).thenThrow(new IOException("unavailable"));
        assertThatThrownBy(() -> zip.download(project)).isInstanceOf(IOException.class);
    }

    @Test
    void crossProjectUpdateCannotChangeAnotherProjectsEntry() {
        var entry = importRows("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처").saved().getFirst();
        var other = service.create(new ProjectInput("다른 프로젝트", LocalDate.now())).id();
        assertThatThrownBy(() -> service.update(other, entry.id(), entry.info())).isInstanceOf(PerformanceNotFoundException.class);
        verifyNoInteractions(files);
    }

    @Test
    void projectDeadlineAndStatusRemainEditableAndSelectionCanBeClearedOffline() {
        var entry = importRows("1\t사업A\t2024.01 ~ 2025.12\t100\t발주처").saved().getFirst();
        assertThat(service.project(project).daysRemaining()).isEqualTo(3);
        assertThat(service.project(project).status()).isEqualTo("COLLECTING");
        service.updateProject(project, new ProjectInput("수정", LocalDate.now().minusDays(1)));
        assertThat(service.project(project).daysRemaining()).isEqualTo(-1);
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "파일.pdf")));
        service.update(project, entry.id(), input(entry.info(), 7L, EvidenceType.CONTRACT, KitcStatus.NEEDED, null, null));
        reset(files);
        service.update(project, entry.id(), input(entry.info(), null, null, KitcStatus.NEEDED, null, null));
        assertThat(repository.entry(project, entry.id()).selectedFilename()).isNull();
        verifyNoInteractions(files);
    }

    @Test
    void resolvesTodayAsOngoingAndRejectsInvalidPeriod() {
        String today = LocalDate.now().toString();
        assertThat(importRows("1\t사업A\t2024-01-01 ~ " + today + "\t100\t발주처").saved().getFirst().resolvedStatus())
                .isEqualTo(BusinessStatus.IN_PROGRESS);
        assertThat(importRows("2\t사업A\t2024.13 ~ 2025.12\t100\t발주처").errors()).hasSize(1);
        assertThat(importRows("3\t사업A\t2025.12 ~ 2024.01\t100\t발주처").errors()).hasSize(1);
    }

    @org.junit.jupiter.api.AfterEach
    void closeConnection() { dataSource.destroy(); }

    @Test
    void recoversAfterUnclosedQuoteAndHandlesUnquotedBusinessNameLineBreak() {
        var result = importRows("1\t\"잘못된 셀\n"
                + "2\t통합\n시스템\t2024.01 ~ 2025.12\t100\t발주처");
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error.message()).contains("따옴표"));
        assertThat(result.saved()).singleElement().satisfies(entry -> {
            assertThat(entry.info().pptNumber()).isEqualTo("2");
            assertThat(entry.info().businessName()).isEqualTo("통합 시스템");
        });
    }

    @Test
    void htmlRejectsMergedRowsButStillSavesFollowingRowsAndRejectsEmptyPaste() {
        var result = service.paste(project, new PasteInput("", """
                <table><tr><td colspan="2">1 사업A</td><td>2024.01 ~ 2025.12</td><td>100</td><td>발주처</td></tr>
                <tr><td>2</td><td>사업B</td><td>2024.01 ~ 2025.12</td><td>100</td><td>발주처</td></tr></table>
                """));
        assertThat(result.errors()).hasSize(1);
        assertThat(result.saved()).hasSize(1);
        assertThatThrownBy(() -> importRows("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsafeZipNamesCannotTraverseDirectoriesAndCollisionsAreReported() throws Exception {
        var entries = importRows("1/\t../사업A\t2024.01 ~ 2025.12\t100\t발주처\n"
                + "1?\t../사업A\t2024.01 ~ 2025.12\t100\t발주처").saved();
        when(files.findActiveFileById(7L)).thenReturn(Optional.of(file(7, "파일.pdf")));
        for (var entry : entries) {
            var updated = service.update(project, entry.id(),
                    input(entry.info(), 7L, EvidenceType.CONTRACT, KitcStatus.NEEDED, null, null));
            assertThat(PerformanceZipService.filename(updated)).doesNotContain("/", "\\", "..");
        }
        var content = mock(PerformanceFileContentPort.class);
        assertThatThrownBy(() -> new PerformanceZipService(repository, content, drive).download(project))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        verifyNoInteractions(content);
    }
    private ImportResult importRows(String rows) { return service.paste(project, new PasteInput(rows, null)); }
    static CompanyFileMetadata file(long id, String name) {
        return new CompanyFileMetadata(id, UUID.randomUUID(), name, "pdf", null, null);
    }
    static EntryInput input(EntryInput old, Long file, EvidenceType type, KitcStatus status, LocalDate requested, LocalDate replied) {
        return new EntryInput(old.pptNumber(), old.businessName(), old.businessPeriod(), old.contractAmount(), old.client(),
                old.businessStatus(), file, type, status, requested, replied);
    }
}