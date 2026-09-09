package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.service.SubmissionCaseService;
import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:submission-upload;DB_CLOSE_DELAY=-1","company-db.enabled=false","external-notice.scheduler.enabled=false"})
class SubmissionUploadTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("submissions.upload-directory",()->storage.toString()); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionCaseService cases;
    @MockitoBean CompanyFileSearchPort companyFiles;
    @Autowired com.comhu.bidmonitor.submission.service.SubmissionUploadService uploads;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final String requirements="""
        [{"category":"COMPANY_GENERAL","documentName":"사업자등록증","sourceReference":"BUSINESS_REGISTRATION"}]
        """;
    private void upload(String name,String content) throws Exception {
        mvc.perform(multipart("/api/submission-document-masters/BUSINESS_REGISTRATION/upload")
                .file(new MockMultipartFile("file",name,"application/pdf",content.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.originalFilename").value(name.substring(name.lastIndexOf('/')+1)))
                .andExpect(jsonPath("$.path").doesNotExist()).andExpect(jsonPath("$.storagePath").doesNotExist());
    }
    private Long project(String name) {return cases.createProject(name,null,List.of(),java.time.LocalDate.of(2026,12,31)).getId();}
    private void collect(Long id) throws Exception {mvc.perform(post("/api/submission-cases/{id}/collect",id).contentType("application/json").content(requirements)).andExpect(status().isOk());}
    @Test void uploadReplacementPreservesExistingProjectsAndZipBytes() throws Exception {
        upload("../../first.pdf","first bytes");
        String first=jdbc.queryForObject("SELECT uploaded_file_id FROM submission_common_document WHERE document_type='BUSINESS_REGISTRATION'",String.class);
        assertThat(Files.readString(storage.resolve(first+".bin"))).isEqualTo("first bytes");
        Long a=project("기존 프로젝트");collect(a);
        Long requirement=jdbc.queryForObject("SELECT id FROM submission_document_requirement WHERE submission_case_id=?",Long.class,a);
        cases.replaceSelections(a,List.of(new SubmissionCaseService.SelectionChoice(requirement,null)));
        Long selection=jdbc.queryForObject("SELECT id FROM submission_document_selection WHERE submission_case_id=?",Long.class,a);
        upload("next.pdf","next bytes");
        String next=jdbc.queryForObject("SELECT uploaded_file_id FROM submission_common_document WHERE document_type='BUSINESS_REGISTRATION'",String.class);
        assertThat(next).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT uploaded_file_id FROM submission_document_selection WHERE id=?",String.class,selection)).isEqualTo(first);
        assertThat(Files.readString(storage.resolve(first+".bin"))).isEqualTo("first bytes");
        Long b=project("새 프로젝트");collect(b);
        assertThat(jdbc.queryForObject("SELECT uploaded_file_id FROM submission_document_selection WHERE submission_case_id=?",String.class,b)).isEqualTo(next);
        mvc.perform(get("/api/submission-cases/{id}/package",a)).andExpect(status().isOk()).andExpect(jsonPath("$.selections[0].uploadedFileId").value(first));
        var pending=mvc.perform(get("/api/submission-cases/{id}/download",a)).andExpect(request().asyncStarted()).andReturn();
        byte[] archive=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {assertThat(zip.getNextEntry().getName()).endsWith("_first.pdf");assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("first bytes");}
        collect(a);
        assertThat(jdbc.queryForObject("SELECT uploaded_file_id FROM submission_document_selection WHERE submission_case_id=?",String.class,a)).isEqualTo(next);
        org.mockito.Mockito.verifyNoInteractions(companyFiles);
    }
    @Test void emptyUploadFailsWithoutReplacingCurrentAndRoutesUseAppShell() throws Exception {
        upload("valid.pdf","valid");
        String before=jdbc.queryForObject("SELECT uploaded_file_id FROM submission_common_document WHERE document_type='BUSINESS_REGISTRATION'",String.class);
        mvc.perform(multipart("/api/submission-document-masters/BUSINESS_REGISTRATION/upload").file(new MockMultipartFile("file","empty.pdf","application/pdf",new byte[0])))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT uploaded_file_id FROM submission_common_document WHERE document_type='BUSINESS_REGISTRATION'",String.class)).isEqualTo(before);
        mvc.perform(get("/documents/")).andExpect(status().isOk()).andExpect(forwardedUrl("/documents/index.html"));
        mvc.perform(get("/api/submission-document-masters")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='BUSINESS_REGISTRATION')].sizeBytes").isNotEmpty());
        mvc.perform(get("/api/submission-common-documents")).andExpect(status().isOk());
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackReferenceDoesNotLeaveAnUploadedFile() {
        var template=new org.springframework.transaction.support.TransactionTemplate(transactions);
        String id=template.execute(status->{var file=uploads.upload("BUSINESS_REGISTRATION",new MockMultipartFile("file","rollback.pdf","application/pdf",new byte[]{1,2,3}));status.setRollbackOnly();return file.id();});
        assertThat(Files.exists(storage.resolve(id+".bin"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submission_uploaded_file WHERE id=?",Long.class,id)).isZero();
    }
}
