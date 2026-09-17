package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriveEvidenceServiceTests {
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private FmsDrivePort port;
    private FmsDriveProperties config;
    private PerformanceDriveFileRepository registry;
    private PerformanceRepository repository;
    private DriveEvidenceService drive;
    private DriveFileIndexRepository index;
    private DriveFileIndexRefreshService refresh;
    private PerformanceService service;
    private CompanyFileSearchPort company;
    private String project;
    private static final String BUSINESS = "2026 AI기반 지역관광 문제해결 프로젝트 가이드라인 수립";

    @BeforeEach
    void setup() {
        ds = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        registry = new PerformanceDriveFileRepository(jdbc);
        repository = new PerformanceRepository(jdbc);
        config = new FmsDriveProperties();
        config.setBaseUrl("http://fms.test");
        config.setIndexRoots(List.of("/cert", "/contracts"));
        config.setCertificateFolders(List.of("/cert"));
        config.setContractFolders(List.of("/contracts"));
        port = mock(FmsDrivePort.class);
        index = new DriveFileIndexRepository(jdbc);
        refresh = new DriveFileIndexRefreshService(port, config, index);
        drive = new DriveEvidenceService(port, config, registry, index);
        company = mock(CompanyFileSearchPort.class);
        service = new PerformanceService(repository, new PerformanceTableParser(), company, drive);
        project = service.create(new ProjectInput("FMS", LocalDate.now())).id();
    }
    @AfterEach void close() { ds.destroy(); }

    @Test
    void matchesScholarshipFoundationAiCallbotDespiteBusinessNameSpacing() {
        String filename = "154_260515_실적증명서(한국장학재단) AI콜봇 구축 사업 개인정보 영향평가.pdf";
        when(port.list("/cert")).thenReturn(List.of(item("/cert", filename)));
        var input = new EntryInput("1", "AI콜봇구축사업 개인정보 영향평가", "2024.01 ~ 2025.12",
                "100", "한국장학재단", BusinessStatus.COMPLETED, null, null, KitcStatus.NEEDED, null, null);
        var entry = repository.insert(project, input);
        var result = indexedCandidates(entry.id());
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.file().originalFilename()).isEqualTo(filename);
            assertThat(candidate.evidenceType()).isEqualTo(EvidenceType.CERTIFICATE);
            assertThat(candidate.reason()).contains("사업명 일치", "발주처 일치");
        });
        assertThat(repository.entry(project, entry.id()).info().selectedDriveFileId()).isNull();
        verify(port, never()).list("/contracts");
        verifyNoInteractions(company);
    }

    @Test
    void completedOnlyRequestsKitcAfterBothSourcesHaveNoMatches() {
        doReturn(List.of()).when(port).list("/cert");
        when(port.list("/contracts")).thenReturn(List.of());
        var result = indexedCandidates(entry("1", false).id());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.nextAction()).isEqualTo("KITC 요청 필요");
        verifyNoInteractions(port);
        verifyNoInteractions(company);
    }
    @Test
    void recommendsCertificateAndCertificateAlternateNameWithFuzzyBusinessClientMatchingOnly() {
        when(port.list("/cert")).thenReturn(List.of(
                item("/cert", "한국관광공사 AI 기반 지역관광 문제해결 가이드라인 실적증명원.pdf"),
                item("/cert", "2026 AI기반 지역관광 문제해결 프로젝트 가이드라인 수립 실적증명서.pdf"),
                item("/cert", "한국관광공사 전혀다른사업 실적증명서.pdf")));
        var entry = entry("1", false);
        var result = indexedCandidates(entry.id());
        assertThat(result.candidates()).hasSize(2).allSatisfy(c -> {
            assertThat(c.file().driveFileId()).isNotBlank();
            assertThat(c.file().fileId()).isNull();
            assertThat(c.evidenceType()).isEqualTo(EvidenceType.CERTIFICATE);
        });
        assertThat(repository.entry(project, entry.id()).info().selectedDriveFileId()).isNull();
        verify(port, never()).list("/contracts");
        verifyNoInteractions(company);
    }

    @Test
    void candidateGetIsReadOnlyStableAndDistinguishesEqualNamesByPath() throws Exception {
        String name = BUSINESS + " 실적증명서.pdf";
        when(port.list("/cert")).thenReturn(List.of(
                new FmsDrivePort.Item("a", "/cert/a", true, 0, null),
                new FmsDrivePort.Item("b", "/cert/b", true, 0, null)));
        when(port.list("/cert/a")).thenReturn(List.of(item("/cert/a", name)));
        when(port.list("/cert/b")).thenReturn(List.of(item("/cert/b", name)));
        refresh.refresh();
        clearInvocations(port);
        var entry = entry("1", false);
        var before = jdbc.queryForList("SELECT * FROM performance_drive_file ORDER BY id");

        var first = service.candidates(project, entry.id());
        var second = service.candidates(project, entry.id());

        assertThat(first.candidates()).hasSize(2);
        assertThat(first.candidates()).extracting(candidate -> candidate.file().originalFilename())
                .containsExactly(name, name);
        assertThat(first.candidates()).extracting(candidate -> candidate.file().driveFileId())
                .doesNotHaveDuplicates().containsExactlyElementsOf(second.candidates().stream()
                        .map(candidate -> candidate.file().driveFileId()).toList());
        assertThat(first.candidates()).extracting(Candidate::reason)
                .containsExactlyElementsOf(second.candidates().stream().map(Candidate::reason).toList());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new PerformanceController(service, mock(PerformanceZipService.class))).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/performance-projects/{projectId}/entries/{entryId}/candidates", project, entry.id()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates.length()").value(2));
        assertThat(jdbc.queryForList("SELECT * FROM performance_drive_file ORDER BY id")).isEqualTo(before);
        verifyNoInteractions(port);

        String candidateId = first.candidates().getFirst().file().driveFileId();
        var selected = service.update(project, entry.id(), selected(entry, candidateId));
        assertThat(selected.info().selectedDriveFileId()).isEqualTo(candidateId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_drive_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void candidateGetReusesRegisteredIdWithoutRefreshingMetadataAndSelectionAloneRegistersThenClears() {
        String name = BUSINESS + " 실적증명서.pdf";
        String path = "/cert/" + name;
        var old = new FmsDrivePort.Item(name, path, false, 1, Instant.parse("2026-01-01T00:00:00Z"));
        var existing = registry.register("http://fms.test", "CNH", old);
        var indexed = new FmsDrivePort.Item(name, path, false, 99, Instant.parse("2026-02-01T00:00:00Z"));
        when(port.list("/cert")).thenReturn(List.of(indexed));
        refresh.refresh();
        var entry = entry("1", false);
        var before = jdbc.queryForMap("SELECT * FROM performance_drive_file WHERE id = ?", existing.id());

        var candidate = service.candidates(project, entry.id()).candidates().getFirst();

        assertThat(candidate.file().driveFileId()).isEqualTo(existing.id());
        assertThat(candidate.file().size()).isEqualTo(99);
        assertThat(jdbc.queryForMap("SELECT * FROM performance_drive_file WHERE id = ?", existing.id())).isEqualTo(before);
        clearInvocations(port);
        var selected = service.update(project, entry.id(), selected(entry, candidate.file().driveFileId()));
        assertThat(selected.info().selectedDriveFileId()).isEqualTo(existing.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_drive_file", Integer.class)).isEqualTo(1);

        var info = selected.info();
        var cleared = service.update(project, entry.id(), new EntryInput(info.pptNumber(), info.businessName(),
                info.businessPeriod(), info.contractAmount(), info.client(), info.businessStatus(), null, null,
                info.kitcStatus(), info.requestedAt(), info.repliedAt(), null, null));
        assertThat(cleared.info().selectedDriveFileId()).isNull();
        assertThat(cleared.selectedFilename()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_drive_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void completedFallsBackToContractOnlyWhenCertificateMatchesAreAbsent() {
        when(port.list("/cert")).thenReturn(List.of(item("/cert", "무관한 실적증명서.pdf")));
        when(port.list("/contracts")).thenReturn(List.of(item("/contracts", BUSINESS + " 계약서.pdf")));
        assertThat(indexedCandidates(entry("1", false).id()).candidates())
                .extracting(Candidate::evidenceType).containsExactly(EvidenceType.CONTRACT);
        verifyNoInteractions(company);
    }

    @Test
    void ongoingNeverQueriesCertificateRootsAndNoMatchesMeansKitc() {
        when(port.list("/contracts")).thenReturn(List.of(item("/contracts", BUSINESS + " 실적증명원.pdf")));
        var result = indexedCandidates(entry("1", true).id());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.nextAction()).isEqualTo("KITC 요청 필요");
        verify(port, never()).list("/cert");
        verifyNoInteractions(company);
    }

    @Test
    void listErrorOrMissingConfigurationCannotMasqueradeAsNoCandidates() {
        var entry = entry("1", false);
        when(port.list("/cert")).thenThrow(new FmsDriveException("FMS 연결 실패"));
        assertThatThrownBy(() -> indexedCandidates(entry.id())).isInstanceOf(FmsDriveException.class);
        verify(port, never()).list("/contracts");
        config.setCertificateFolders(List.of());
        assertThatThrownBy(() -> indexedCandidates(entry.id())).hasMessageContaining("설정");
    }

    @Test
    void followsSubfoldersAndRejectsOutsidePathsOrIncompleteTraversal() {
        when(port.list("/cert")).thenReturn(List.of(new FmsDrivePort.Item("2026", "/cert/2026", true, 0, null)));
        when(port.list("/cert/2026")).thenReturn(List.of(item("/cert/2026", BUSINESS + " 실적증명원.pdf")));
        var entry = entry("1", false);
        assertThat(indexedCandidates(entry.id()).candidates()).hasSize(1);
        config.setMaxDepth(0);
        assertThatThrownBy(() -> indexedCandidates(entry.id())).hasMessageContaining("인덱스");
        assertThat(index.state("http://fms.test", "CNH", "/cert").status()).isEqualTo("FAILED");
        when(port.list("/cert")).thenReturn(List.of(item("/other", BUSINESS + " 실적증명원.pdf")));
        assertThatThrownBy(() -> indexedCandidates(entry.id())).hasMessageContaining("인덱스");
    }
    @Test
    void driveSelectionSurvivesSchemaRerunAndSameFileCanProduceTwoZipEntries() throws Exception {
        var file = item("/cert", BUSINESS + " 실적증명원.pdf");
        when(port.list("/cert")).thenReturn(List.of(file));
        var first = entry("01", false);
        var second = entry("02", false);
        String ref = indexedCandidates(first.id()).candidates().getFirst().file().driveFileId();
        service.update(project, first.id(), selected(first, ref));
        service.update(project, second.id(), selected(second, ref));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        assertThat(repository.entry(project, first.id()).info().selectedDriveFileId()).isEqualTo(ref);
        assertThat(repository.entry(project, first.id()).info().selectedFileId()).isNull();
        assertThat(repository.project(project).status()).isEqualTo("READY");
        when(port.canDownload(file.path())).thenReturn(true);
        when(port.download(file.path())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        var legacy = mock(PerformanceFileContentPort.class);
        byte[] bytes = new PerformanceZipService(repository, legacy, drive).download(project);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            assertThat(zip.getNextEntry().getName()).startsWith("01_실적증명서_(한국관광공사)");
            assertThat(zip.readAllBytes()).containsExactly(1, 2, 3);
            assertThat(zip.getNextEntry().getName()).startsWith("02_실적증명서_(한국관광공사)");
            assertThat(zip.readAllBytes()).containsExactly(1, 2, 3);
            assertThat(zip.getNextEntry()).isNull();
        }
        verifyNoInteractions(legacy, company);
        verify(port, times(2)).canDownload(file.path());
    }

    @Test
    void deniedDrivePermissionNeverFallsBackToNasOrDownloadsAnyBytes() throws Exception {
        var file = item("/cert", BUSINESS + " 실적증명원.pdf");
        when(port.list("/cert")).thenReturn(List.of(file));
        var entry = entry("1", false);
        String id = indexedCandidates(entry.id()).candidates().getFirst().file().driveFileId();
        service.update(project, entry.id(), selected(entry, id));
        when(port.canDownload(file.path())).thenReturn(false);
        var legacy = mock(PerformanceFileContentPort.class);
        assertThatThrownBy(() -> new PerformanceZipService(repository, legacy, drive).download(project))
                .isInstanceOf(FmsDriveException.class).hasMessageContaining("권한");
        verify(port, never()).download(anyString());
        verifyNoInteractions(legacy);
    }

    @Test
    void selectionRejectsUnknownOrMissingFilesAndNeverAcceptsBothSources() {
        var entry = entry("1", false);
        when(port.list("/cert")).thenReturn(List.of());
        when(port.list("/contracts")).thenReturn(List.of());
        refresh.refresh();
        assertThatThrownBy(() -> service.update(project, entry.id(), selected(entry, UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class);
        var file = item("/cert", BUSINESS + " 실적증명서.pdf");
        when(port.list("/cert")).thenReturn(List.of(file));
        String id = indexedCandidates(entry.id()).candidates().getFirst().file().driveFileId();
        doReturn(List.of()).when(port).list("/cert");
        assertThatThrownBy(() -> service.update(project, entry.id(), selected(entry, id))).hasMessageContaining("다시 조회");
        var input = selected(entry, id);
        assertThatThrownBy(() -> service.update(project, entry.id(), new EntryInput(input.pptNumber(),
                input.businessName(), input.businessPeriod(), input.contractAmount(), input.client(), null,
                5L, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, id))).hasMessageContaining("하나만");
    }

    private Recommendations indexedCandidates(String id) {
        refresh.refresh();
        clearInvocations(port);
        var result = service.candidates(project, id);
        verifyNoInteractions(port);
        return result;
    }

    @Test
    void unbuiltAndFailedIndexesNeverLookLikeAnEmptySuccessfulIndex() {
        var entry = entry("1", false);
        assertThatThrownBy(() -> service.candidates(project, entry.id())).hasMessageContaining("인덱스");
        doReturn(List.of()).when(port).list("/cert");
        when(port.list("/contracts")).thenReturn(List.of());
        refresh.refresh();
        assertThat(service.candidates(project, entry.id()).candidates()).isEmpty();
        when(port.list("/cert")).thenThrow(new FmsDriveException("secret path cookie"));
        refresh.refresh();
        assertThatThrownBy(() -> service.candidates(project, entry.id())).hasMessageContaining("인덱스");
    }

    @Test
    void refreshPreservesSnapshotOnFailureDeduplicatesRootsAndExcludesHiddenFiles() {
        config.setIndexRoots(List.of("/cert", "/cert/"));
        config.setCertificateFolders(List.of("/cert", "/cert/"));
        config.setContractFolders(List.of("/cert"));
        var file = item("/cert", BUSINESS + " 실적증명서.pdf");
        when(port.list("/cert")).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(file, item("/cert", ".hidden.pdf"), item("/cert", "~$temp.pdf"));
        });
        assertThat(refresh.refresh()).hasSize(1);
        verify(port, times(1)).list("/cert");
        assertThat(index.files("http://fms.test", "CNH", "/cert")).hasSize(1);
        var before = index.state("http://fms.test", "CNH", "/cert");
        when(port.list("/cert")).thenThrow(new FmsDriveException("private-path secret"));
        refresh.refresh();
        var after = index.state("http://fms.test", "CNH", "/cert");
        assertThat(after.status()).isEqualTo("FAILED");
        assertThat(after.lastSuccessAt()).isEqualTo(before.lastSuccessAt());
        assertThat(after.fileCount()).isEqualTo(1);
        assertThat(after.errorCode()).isEqualTo("REFRESH_FAILED");
        var jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_file_index", Integer.class)).isEqualTo(1);
        doReturn(List.of()).when(port).list("/cert");
        refresh.refresh();
        assertThat(index.files("http://fms.test", "CNH", "/cert")).isEmpty();
        assertThat(index.state("http://fms.test", "CNH", "/cert").status()).isEqualTo("SUCCESS");
    }

    @Test
    void indexReplacementRollsBackAllChangesWhenOneMetadataRowCannotBeStored() {
        when(port.list("/cert")).thenReturn(List.of(item("/cert", "old.pdf")));
        refresh.refresh();
        index.started("http://fms.test", "CNH", "/cert");
        assertThatThrownBy(() -> index.replace("http://fms.test", "CNH", "/cert",
                List.of(item("/cert", "new.pdf"), item("/cert", "x".repeat(2100))), 1)).isInstanceOf(RuntimeException.class);
        assertThat(new JdbcTemplate(ds).queryForObject("SELECT name FROM drive_file_index WHERE root='/cert'", String.class)).isEqualTo("old.pdf");
    }
    @Test
    void overlappingRootsDeduplicateCandidatesAndRepeatedSearchDoesNotCallFms() {
        config.setCertificateFolders(List.of("/cert", "/cert/2026"));
        when(port.list("/cert")).thenReturn(List.of(new FmsDrivePort.Item("2026", "/cert/2026", true, 0, null)));
        when(port.list("/cert/2026")).thenReturn(List.of(item("/cert/2026", BUSINESS + " 실적증명서.pdf")));
        refresh.refresh();
        var entry = entry("1", false);
        clearInvocations(port);
        assertThat(service.candidates(project, entry.id()).candidates()).hasSize(1);
        assertThat(service.candidates(project, entry.id()).candidates()).hasSize(1);
        verifyNoInteractions(port);
    }

    @Test
    void limitsAndConcurrentRefreshFailWithoutPublishingPartialSnapshots() {
        config.setMaxFiles(1);
        when(port.list("/cert")).thenReturn(List.of(item("/cert", "a.pdf"), item("/cert", "b.pdf")));
        refresh.refresh();
        assertThat(index.state("http://fms.test", "CNH", "/cert").status()).isEqualTo("FAILED");
        config.setMaxFolders(1);
        when(port.list("/cert")).thenReturn(List.of(new FmsDrivePort.Item("child", "/cert/child", true, 0, null)));
        refresh.refresh();
        assertThat(index.state("http://fms.test", "CNH", "/cert").status()).isEqualTo("FAILED");
        when(port.list("/cert")).thenAnswer(call -> {
            assertThatThrownBy(() -> refresh.refresh()).hasMessageContaining("이미 갱신");
            return List.of();
        });
        refresh.refresh();
        assertThat(index.state("http://fms.test", "CNH", "/cert").status()).isEqualTo("SUCCESS");
    }

    @Test
    void managementApiExposesOnlySafeStatusAndCounts() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new DriveFileIndexController(refresh))
                .setControllerAdvice(new PerformanceApiExceptionHandler()).build();
        var initial = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/drive-index"))
                .andReturn().getResponse();
        assertThat(initial.getStatus()).isEqualTo(200);
        assertThat(initial.getContentAsString()).contains("NOT_BUILT").doesNotContain("/cert", "fms.test", "session");
        when(port.list("/cert")).thenThrow(new FmsDriveException("/private secret SESSION=token"));
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/drive-index/refresh"))
                .andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getContentAsString()).contains("FAILED", "REFRESH_FAILED")
                .doesNotContain("/private", "secret", "SESSION", "/cert", "fms.test");
    }
    @Test
    void sharedBusinessRootIndexesAllFeaturesAndEvidenceSearchStaysWithinItsFolder() {
        config.setIndexRoots(List.of("/business"));
        config.setCertificateFolders(List.of("/business/cert"));
        config.setContractFolders(List.of("/business/contracts"));
        when(port.list("/business")).thenReturn(List.of(
                new FmsDrivePort.Item("cert", "/business/cert", true, 0, null),
                new FmsDrivePort.Item("team", "/business/team", true, 0, null),
                item("/business", BUSINESS + " 실적증명서.pdf")));
        when(port.list("/business/cert")).thenReturn(List.of(item("/business/cert", BUSINESS + " 실적증명서.pdf")));
        String parent = "/business/team";
        for (String child : List.of("archive", "2026", "people", "01_프로필")) {
            String path = parent + "/" + child;
            when(port.list(parent)).thenReturn(List.of(new FmsDrivePort.Item(child, path, true, 0, null)));
            parent = path;
        }
        when(port.list(parent)).thenReturn(List.of(item(parent, "프로필_김종수_20260901.pdf")));
        refresh.refresh();
        assertThat(index.state("http://fms.test", "CNH", "/business").status()).isEqualTo("SUCCESS");
        assertThat(index.files("http://fms.test", "CNH", "/business")).hasSize(3);
        clearInvocations(port);
        assertThat(service.candidates(project, entry("1", false).id()).candidates()).hasSize(1);
        assertThat(service.candidates(project, entry("2", true).id()).candidates()).isEmpty();
        verifyNoInteractions(port);
        var before = index.state("http://fms.test", "CNH", "/business");
        when(port.list(parent)).thenThrow(new FmsDriveException("read failed"));
        refresh.refresh();
        var after = index.state("http://fms.test", "CNH", "/business");
        assertThat(after.status()).isEqualTo("FAILED");
        assertThat(after.lastSuccessAt()).isEqualTo(before.lastSuccessAt());
        assertThat(after.fileCount()).isEqualTo(3);
        assertThatThrownBy(() -> service.candidates(project, entry("3", false).id())).isInstanceOf(FmsDriveException.class);
    }

    private Entry entry(String number, boolean ongoing) {
        return service.paste(project, new PasteInput(number + "\t" + BUSINESS
                + (ongoing ? "\t2024.01 ~ 수행중" : "\t2024.01 ~ 2025.12") + "\t100\t한국관광공사", null)).saved().getFirst();
    }
    private FmsDrivePort.Item item(String parent, String name) {
        return new FmsDrivePort.Item(name, parent + "/" + name, false, 3, null);
    }
    private EntryInput selected(Entry entry, String id) {
        var e = entry.info();
        return new EntryInput(e.pptNumber(), e.businessName(), e.businessPeriod(), e.contractAmount(), e.client(),
                e.businessStatus(), null, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, id);
    }
}
