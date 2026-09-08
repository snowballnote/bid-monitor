package com.comhu.bidmonitor.performance;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriveEvidenceServiceTests {
    private SingleConnectionDataSource ds;
    private FmsDrivePort port;
    private FmsDriveProperties config;
    private PerformanceDriveFileRepository registry;
    private PerformanceRepository repository;
    private DriveEvidenceService drive;
    private PerformanceService service;
    private CompanyFileSearchPort company;
    private String project;
    private static final String BUSINESS = "2026 AI기반 지역관광 문제해결 프로젝트 가이드라인 수립";

    @BeforeEach
    void setup() {
        ds = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        registry = new PerformanceDriveFileRepository(jdbc);
        repository = new PerformanceRepository(jdbc);
        config = new FmsDriveProperties();
        config.setBaseUrl("http://fms.test");
        config.setCertificateFolders(List.of("/cert"));
        config.setContractFolders(List.of("/contracts"));
        port = mock(FmsDrivePort.class);
        drive = new DriveEvidenceService(port, config, registry);
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
        var result = service.candidates(project, entry.id());
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
        when(port.list("/cert")).thenReturn(List.of());
        when(port.list("/contracts")).thenReturn(List.of());
        var result = service.candidates(project, entry("1", false).id());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.nextAction()).isEqualTo("KITC 요청 필요");
        var order = inOrder(port);
        order.verify(port).list("/cert");
        order.verify(port).list("/contracts");
        verifyNoInteractions(company);
    }
    @Test
    void recommendsCertificateAndCertificateAlternateNameWithFuzzyBusinessClientMatchingOnly() {
        when(port.list("/cert")).thenReturn(List.of(
                item("/cert", "한국관광공사 AI 기반 지역관광 문제해결 가이드라인 실적증명원.pdf"),
                item("/cert", "2026 AI기반 지역관광 문제해결 프로젝트 가이드라인 수립 실적증명서.pdf"),
                item("/cert", "한국관광공사 전혀다른사업 실적증명서.pdf")));
        var entry = entry("1", false);
        var result = service.candidates(project, entry.id());
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
    void completedFallsBackToContractOnlyWhenCertificateMatchesAreAbsent() {
        when(port.list("/cert")).thenReturn(List.of(item("/cert", "무관한 실적증명서.pdf")));
        when(port.list("/contracts")).thenReturn(List.of(item("/contracts", BUSINESS + " 계약서.pdf")));
        assertThat(service.candidates(project, entry("1", false).id()).candidates())
                .extracting(Candidate::evidenceType).containsExactly(EvidenceType.CONTRACT);
        verifyNoInteractions(company);
    }

    @Test
    void ongoingNeverQueriesCertificateRootsAndNoMatchesMeansKitc() {
        when(port.list("/contracts")).thenReturn(List.of(item("/contracts", BUSINESS + " 실적증명원.pdf")));
        var result = service.candidates(project, entry("1", true).id());
        assertThat(result.candidates()).isEmpty();
        assertThat(result.nextAction()).isEqualTo("KITC 요청 필요");
        verify(port, never()).list("/cert");
        verifyNoInteractions(company);
    }

    @Test
    void listErrorOrMissingConfigurationCannotMasqueradeAsNoCandidates() {
        var entry = entry("1", false);
        when(port.list("/cert")).thenThrow(new FmsDriveException("FMS 연결 실패"));
        assertThatThrownBy(() -> service.candidates(project, entry.id())).isInstanceOf(FmsDriveException.class);
        verify(port, never()).list("/contracts");
        config.setCertificateFolders(List.of());
        assertThatThrownBy(() -> service.candidates(project, entry.id())).hasMessageContaining("설정");
    }

    @Test
    void followsSubfoldersAndRejectsOutsidePathsOrIncompleteTraversal() {
        when(port.list("/cert")).thenReturn(List.of(new FmsDrivePort.Item("2026", "/cert/2026", true, 0, null)));
        when(port.list("/cert/2026")).thenReturn(List.of(item("/cert/2026", BUSINESS + " 실적증명원.pdf")));
        var entry = entry("1", false);
        assertThat(service.candidates(project, entry.id()).candidates()).hasSize(1);
        config.setMaxDepth(0);
        assertThatThrownBy(() -> service.candidates(project, entry.id())).hasMessageContaining("깊이");
        when(port.list("/cert")).thenReturn(List.of(item("/other", BUSINESS + " 실적증명원.pdf")));
        assertThatThrownBy(() -> service.candidates(project, entry.id())).hasMessageContaining("범위");
    }

    @Test
    void driveSelectionSurvivesSchemaRerunAndSameFileCanProduceTwoZipEntries() throws Exception {
        var file = item("/cert", BUSINESS + " 실적증명원.pdf");
        when(port.list("/cert")).thenReturn(List.of(file));
        var first = entry("01", false);
        var second = entry("02", false);
        String ref = service.candidates(project, first.id()).candidates().getFirst().file().driveFileId();
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
        String id = service.candidates(project, entry.id()).candidates().getFirst().file().driveFileId();
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
        assertThatThrownBy(() -> service.update(project, entry.id(), selected(entry, UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class);
        var file = item("/cert", BUSINESS + " 실적증명서.pdf");
        when(port.list("/cert")).thenReturn(List.of(file));
        String id = service.candidates(project, entry.id()).candidates().getFirst().file().driveFileId();
        when(port.list("/cert")).thenReturn(List.of());
        assertThatThrownBy(() -> service.update(project, entry.id(), selected(entry, id))).hasMessageContaining("다시 조회");
        var input = selected(entry, id);
        assertThatThrownBy(() -> service.update(project, entry.id(), new EntryInput(input.pptNumber(),
                input.businessName(), input.businessPeriod(), input.contractAmount(), input.client(), null,
                5L, EvidenceType.CERTIFICATE, KitcStatus.NEEDED, null, null, id))).hasMessageContaining("하나만");
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