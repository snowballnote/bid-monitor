package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.performance.*;
import com.comhu.bidmonitor.submission.service.*;
import com.comhu.bidmonitor.submission.service.SubmissionPersonnelService.Type;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:personnel;DB_CLOSE_DELAY=-1", "company-db.enabled=false", "external-notice.scheduler.enabled=false", "performance.drive.base-url=http://fms.test"})
class SubmissionPersonnelTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("submissions.upload-directory", () -> storage.toString()); }
    @Autowired SubmissionPersonnelService service;
    @Autowired SubmissionCaseService cases;
    @Autowired SubmissionUploadService uploads;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DriveFileIndexRefreshService refresh;
    @Autowired FmsDriveProperties driveProperties;
    @MockitoBean FmsDrivePort drive;
    Long caseId;
    String personId;
    @BeforeEach void setup() {
        caseId = cases.createProject("인력 수집", null, List.of(), LocalDate.of(2026,12,31)).getId();
        jdbc.update("INSERT INTO drive_index_root_state(source,company,root,status,folder_count,file_count) VALUES('http://fms.test','CNH','/컴앤휴먼_컨설팅_인력','SUCCESS',1,0)");
        index("프로필_김대원_20260101.pdf", "2026-01-01 00:00:00");
        personId = service.add(caseId, "김대원", "개발팀").getFirst().id();
        jdbc.update("DELETE FROM drive_file_index");
    }
    void index(String filename, String modified) {
        String folder = filename.contains("인증서") ? "04_개인정보 영향평가 전문인력 인증서" : filename.contains("KOSA") ? "03_KOSA경력증명서" : filename.contains("자격") ? "02_자격사본" : "01_프로필";
        indexPath(filename, "/컴앤휴먼_컨설팅_인력/개발팀/" + folder + "/" + filename, modified);
    }
    void indexPath(String filename, String path, String modified) {
        jdbc.update("INSERT INTO drive_file_index(source,company,root,name,path,ext,size,last_modified,indexed_at) VALUES('http://fms.test','CNH','/컴앤휴먼_컨설팅_인력',?,?,'pdf',10,?,CURRENT_TIMESTAMP)", filename, path, java.sql.Timestamp.valueOf(modified));
    }
    @Test void personnelSearchUsesFilesRecursivelyIndexedFromSharedBusinessRoot() {
        var previousRoots = driveProperties.getIndexRoots();
        try {
            driveProperties.setIndexRoots(List.of("/컴앤휴먼_감리_인력"));
            when(drive.list("/컴앤휴먼_감리_인력")).thenReturn(List.of(new FmsDrivePort.Item("개발팀", "/컴앤휴먼_감리_인력/개발팀", true, 0, null)));
            when(drive.list("/컴앤휴먼_감리_인력/개발팀")).thenReturn(List.of(new FmsDrivePort.Item("01_프로필", "/컴앤휴먼_감리_인력/개발팀/01_프로필", true, 0, null)));
            when(drive.list("/컴앤휴먼_감리_인력/개발팀/01_프로필")).thenReturn(List.of(
                    new FmsDrivePort.Item("프로필_김종수_20260901.pdf", "/컴앤휴먼_감리_인력/개발팀/01_프로필/프로필_김종수_20260901.pdf", false, 10, null)));
            refresh.refresh();
            clearInvocations(drive);
            assertThat(service.search(caseId, "김종수")).singleElement().satisfies(person -> {
                assertThat(person.name()).isEqualTo("김종수");
                assertThat(person.department()).isEqualTo("개발팀");
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drive_file_index WHERE root='/컴앤휴먼_감리_인력'", Integer.class)).isEqualTo(1);
            verifyNoInteractions(drive);
        } finally { driveProperties.setIndexRoots(previousRoots); }
    }
    @Test void irregularConsultingFilesFindChoiAndGroupByFilenameBeforePath() {
        String root = "/컴앤휴먼_컨설팅_인력/프로필/";
        for (String name : List.of("최효재 이력서(26.03).pptx", "KOSA 경력증명서_최효재_20240617.pdf",
                "KOSA경력증명서_최효재_20260902.pdf", "최효재_경력,자격증_2026.08.28.pptx", "최효재_경력,자격증_2026.09.02.pptx")) {
            indexPath(name, root + (name.contains("이력서") ? "1 프로필/" : "2 자격사본/") + name, "2026-09-01 00:00:00");
        }
        assertThat(service.search(caseId, "최효재")).containsExactly(new SubmissionPersonnelService.PersonOption("최효재", ""));
        String choi = service.add(caseId, "최효재", "").stream().filter(person -> person.name().equals("최효재")).findFirst().orElseThrow().id();
        assertThat(service.candidates(caseId, choi, Type.PROFILE)).singleElement().satisfies(file -> {
            assertThat(file.filename()).contains("이력서");
            assertThat(file.recommended()).isFalse(); // Month-only dates are not invented as exact dates.
        });
        assertThat(service.candidates(caseId, choi, Type.KOSA)).hasSize(2).first().satisfies(file -> {
            assertThat(file.filename()).contains("20260902"); assertThat(file.recommended()).isTrue();
        });
        assertThat(service.candidates(caseId, choi, Type.QUALIFICATION)).hasSize(2).first().satisfies(file -> {
            assertThat(file.filename()).contains("2026.09.02"); assertThat(file.recommended()).isTrue();
        });
        indexPath("KOSA_자격사본_최효재.pdf", root + "2 자격사본/KOSA_자격사본_최효재.pdf", "2026-09-01 00:00:00");
        indexPath("KOSA_최효재_김대원.pdf", root + "2 자격사본/KOSA_최효재_김대원.pdf", "2026-09-01 00:00:00");
        assertThat(service.candidates(caseId, choi, Type.KOSA)).hasSize(2);
        assertThat(service.candidates(caseId, choi, Type.QUALIFICATION)).hasSize(2);
        assertThat(service.list(caseId).stream().filter(person -> person.id().equals(choi)).findFirst().orElseThrow().documents())
                .allMatch(doc -> doc.filename() == null);
        verifyNoInteractions(drive);
    }

    @Test void allFourRootsAcceptArbitraryDepthAndKeywordFoldersWithoutNumbering() {
        for (String root : List.of("/컴앤휴먼_감리_인력", "/컴앤휴먼_개인정보_인력", "/컴앤휴먼_인공지능본부_인력", "/컴앤휴먼_컨설팅_인력")) {
            indexPath("김대원_20260902.pdf", root + "/보관/2026/부서/9 자격/김대원_20260902.pdf", "2026-09-01 00:00:00");
            indexPath("경력증명서_김대원_20260902.pdf", root + "/기타/경력증명서_김대원_20260902.pdf", "2026-09-01 00:00:00");
            indexPath("전문인력 인증서_김대원_20260902.pdf", root + "/기타/전문인력 인증서_김대원_20260902.pdf", "2026-09-01 00:00:00");
        }
        assertThat(service.candidates(caseId, personId, Type.QUALIFICATION)).hasSize(4);
        assertThat(service.candidates(caseId, personId, Type.KOSA)).hasSize(4);
        assertThat(service.candidates(caseId, personId, Type.PIA)).hasSize(4).allMatch(file -> !file.recommended());
    }
    @Test void filenameDateBeatsModifiedDateAndSelectionIsExplicitAndScoped() {
        index("프로필_김대원_2026.07.23.pdf", "2025-01-01 00:00:00");
        index("프로필_김대원_20260314.pdf", "2026-09-01 00:00:00");
        index("프로필_김대원정_20260901.pdf", "2026-09-01 00:00:00");
        index("자격사본_김대원_20260801.pdf", "2026-09-01 00:00:00");
        index("프로필_박민수_20260901.pdf", "2026-09-01 00:00:00");
        var candidates = service.candidates(caseId, personId, Type.PROFILE);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst().filename()).contains("2026.07.23");
        assertThat(candidates.getFirst().recommended()).isTrue();
        assertThat(service.list(caseId).getFirst().documents()).allMatch(doc -> doc.filename() == null);
        service.needed(caseId, personId, Type.PROFILE, true);
        service.select(caseId, personId, Type.PROFILE, candidates.getLast().id());
        assertThat(service.list(caseId).getFirst().documents()).anyMatch(doc -> doc.type() == Type.PROFILE && doc.latestStatus().equals("더 최신 후보 있음"));
        assertThat(cases.projects().stream().filter(p -> p.id().equals(caseId)).findFirst().orElseThrow().prepared()).isEqualTo(1);
        assertThatThrownBy(() -> service.select(caseId, personId, Type.KOSA, candidates.getFirst().id())).isInstanceOf(IllegalArgumentException.class);
        Long other = cases.createProject("다른 프로젝트", null, List.of(), LocalDate.of(2026,12,31)).getId();
        assertThatThrownBy(() -> service.candidates(other, personId, Type.PROFILE)).isInstanceOf(SubmissionNotFoundException.class);
        verifyNoInteractions(drive);
    }
    @Test void certificatesHaveNoNewestRecommendationAndUnknownDatesAreNotLatest() {
        index("영향평가 전문인력 인증서_김대원_20180101.pdf", "2026-09-01 00:00:00");
        index("영향평가 전문인력 인증서_김대원_20260101.pdf", "2026-09-01 00:00:00");
        assertThat(service.candidates(caseId, personId, Type.PIA)).hasSize(2).allMatch(file -> !file.recommended());
        index("KOSA_김대원_날짜없음.pdf", "2026-09-01 00:00:00");
        assertThat(service.candidates(caseId, personId, Type.KOSA).getFirst().recommended()).isFalse();
        assertThat(SubmissionPersonnelService.filenameDate("프로필_20260230.pdf")).isNull();
        assertThat(SubmissionPersonnelService.filenameDate("프로필_2026-7-3.pdf")).isEqualTo(LocalDate.of(2026,7,3));
    }
    @Test void directUploadSurvivesUncheckAndZipContainsOnlyNeededDocuments() throws Exception {
        service.needed(caseId, personId, Type.PROFILE, true);
        mvc.perform(multipart("/api/submission-cases/{id}/people/{person}/documents/PROFILE/upload", caseId, personId)
                .file(new MockMultipartFile("file", "../../프로필_20260911.pdf", "application/pdf", "profile bytes".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].documents[?(@.type=='PROFILE')].source").value("PC"));
        service.upload(caseId, personId, Type.KOSA, new MockMultipartFile("file", "KOSA.pdf", "application/pdf", "excluded".getBytes()));
        service.needed(caseId, personId, Type.PROFILE, false);
        assertThat(service.collected(caseId)).isEmpty();
        assertThat(service.list(caseId).getFirst().documents()).anyMatch(doc -> doc.type() == Type.PROFILE && doc.filename().equals("프로필_20260911.pdf"));
        service.needed(caseId, personId, Type.PROFILE, true);
        var output = new ByteArrayOutputStream(); uploads.zip(caseId).writeTo(output);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(zip.getNextEntry().getName()).startsWith("인력/김대원_").endsWith("PROFILE_프로필_20260911.pdf");
            assertThat(new String(zip.readAllBytes())).isEqualTo("profile bytes"); assertThat(zip.getNextEntry()).isNull();
        }
        service.select(caseId, personId, Type.PROFILE, null);
        assertThat(service.collected(caseId)).isEmpty();
        verifyNoInteractions(drive);
    }
    @Test void searchUsesOnlyPersonnelFoldersAndDeduplicatesNames() throws Exception {
        assertThat(service.search(caseId, "김대")).isEmpty(); // Saved H2 people never supply search results.
        index("프로필_김민수_20260101.pdf", "2026-01-01 00:00:00");
        index("자격사본_김민수_20260201.pdf", "2026-02-01 00:00:00");
        index("KOSA_김민수_20260301.pdf", "2026-03-01 00:00:00");
        index("영향평가 전문인력 인증서_김민수_20180101.pdf", "2026-03-01 00:00:00");
        indexPath("프로필_김영수.pdf", "/실적/계약서/프로필_김영수.pdf", "2026-01-01 00:00:00");
        indexPath("프로필_김철수.pdf", "/컴앤휴먼_컨설팅_인력_백업/01_프로필/프로필_김철수.pdf", "2026-01-01 00:00:00");
        var employee = new SubmissionPersonnelService.PersonOption("김민수", "개발팀");
        assertThat(service.search(caseId, "김")).containsExactly(employee);
        service.add(caseId, "김민수", "개발팀");
        assertThatThrownBy(() -> service.add(caseId, "김민수", "개발팀")).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/submission-cases/{id}/people/{person}/documents/INVALID/candidates", caseId, personId)).andExpect(status().isBadRequest());
        jdbc.update("UPDATE drive_index_root_state SET status='FAILED'");
        assertThatThrownBy(() -> service.search(caseId, "김")).isInstanceOf(FmsDriveException.class);
        mvc.perform(get("/api/submission-cases/{id}/people/{person}/documents/PROFILE/candidates", caseId, personId)).andExpect(status().isServiceUnavailable());
        service.upload(caseId, personId, Type.PROFILE, new MockMultipartFile("file", "profile.pdf", "application/pdf", new byte[]{1}));
        assertThat(service.list(caseId).stream().filter(person -> person.id().equals(personId)).findFirst().orElseThrow().documents())
                .anyMatch(doc -> "PC".equals(doc.source()));
        verifyNoInteractions(drive);
    }
    @Test void additionRechecksIndexNameAndDepartment() throws Exception {
        index("프로필_김민수_20260101.pdf", "2026-01-01 00:00:00");
        mvc.perform(post("/api/submission-cases/{id}/people", caseId).contentType("application/json")
                .content("{\"name\":\"김민수\",\"department\":\"개발팀\"}"))
                .andExpect(status().isOk());
        assertThat(service.list(caseId)).hasSize(2).anyMatch(p -> p.name().equals("김민수") && p.department().equals("개발팀"));
        mvc.perform(post("/api/submission-cases/{id}/people", caseId).contentType("application/json")
                .content("{\"name\":\"임의 인력\",\"department\":\"개발팀\"}"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> service.add(caseId, "김민수", "위조 부서")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedIndexNeverFallsBackToSavedPeople() throws Exception {
        jdbc.update("UPDATE drive_index_root_state SET status='FAILED'");
        mvc.perform(get("/api/submission-cases/{id}/people/search", caseId).param("q", "김대"))
                .andExpect(status().isServiceUnavailable());
        assertThat(service.list(caseId)).hasSize(1);
        verifyNoInteractions(drive);
    }
    @Test void nameExtractionSkipsAmbiguousAndTemplateNamesAndDepartmentConflicts() {
        assertThat(SubmissionPersonnelService.extractName("프로필김대원(2026.07.23)_최종본.pdf")).isEqualTo("김대원");
        assertThat(SubmissionPersonnelService.extractName("자격사본_남궁민_2026-07-23.pdf")).isEqualTo("남궁민");
        assertThat(SubmissionPersonnelService.extractName("KOSA_김민수_박민수.pdf")).isNull();
        assertThat(SubmissionPersonnelService.extractName("프로필_양식.pdf")).isNull();
        indexPath("김민수_20260901.pdf", "/컴앤휴먼_컨설팅_인력/01_프로필/김민수_20260901.pdf", "2026-09-01 00:00:00");
        assertThat(service.search(caseId, "김민")).containsExactly(new SubmissionPersonnelService.PersonOption("김민수", ""));
        index("자격사본_김민수_20260101.pdf", "2026-01-01 00:00:00");
        assertThat(service.search(caseId, "김민").getFirst().department()).isEqualTo("개발팀");
        indexPath("김민수.pdf", "/컴앤휴먼_컨설팅_인력/운영팀/03_KOSA경력증명서/김민수.pdf", "2026-09-01 00:00:00");
        assertThat(service.search(caseId, "김민")).containsExactly(new SubmissionPersonnelService.PersonOption("김민수", ""));
    }
    @Test void unrelatedFailedRootDoesNotBlockPersonnelAndFilenameOverridesFolder() {
        jdbc.update("INSERT INTO drive_index_root_state(source,company,root,status,folder_count,file_count) VALUES('http://fms.test','CNH','/실적','FAILED',0,0)");
        indexPath("김대원_20260901.pdf", "/컴앤휴먼_컨설팅_인력/개발팀/01_프로필/김대원_20260901.pdf", "2026-09-01 00:00:00");
        indexPath("프로필_김대원_20260902.pdf", "/컴앤휴먼_컨설팅_인력/개발팀/02_자격사본/프로필_김대원_20260902.pdf", "2026-09-01 00:00:00");
        assertThat(service.search(caseId, "김대")).hasSize(1);
        assertThat(service.candidates(caseId, personId, Type.PROFILE)).hasSize(2).anyMatch(file -> file.filename().equals("프로필_김대원_20260902.pdf"));
    }
    @Test void remoteZipChecksPermissionAndOnlyReadsSelectedSource() throws Exception {
        index("프로필_김대원_20260911.pdf", "2026-09-11 00:00:00");
        service.needed(caseId, personId, Type.PROFILE, true);
        service.select(caseId, personId, Type.PROFILE, service.candidates(caseId, personId, Type.PROFILE).getFirst().id());
        String source = "/컴앤휴먼_컨설팅_인력/개발팀/01_프로필/프로필_김대원_20260911.pdf";
        when(drive.canDownload(source)).thenReturn(true);
        when(drive.download(source)).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        uploads.zip(caseId).writeTo(new ByteArrayOutputStream());
        verify(drive).canDownload(source); verify(drive).download(source); verifyNoMoreInteractions(drive);
    }
}
