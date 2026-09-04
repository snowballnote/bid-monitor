package com.comhu.bidmonitor.submission.api;

import com.comhu.bidmonitor.submission.port.CompanyFileSearchPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:common-submission-document-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "company-db.enabled=false",
        "external-notice.scheduler.enabled=false"
})
class CommonSubmissionDocumentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyFileSearchPort fileSearchPort;

    @Test
    void listsAdministrativeCatalogAndStoresOnlySafeFileReference() throws Exception {
        mockMvc.perform(get("/api/submission-common-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[?(@.documentType == 'BUSINESS_REGISTRATION')].refreshPolicy")
                        .value(hasItem("NONE")))
                .andExpect(jsonPath("$[?(@.documentType == 'CORPORATE_SEAL_CERTIFICATE')].refreshIntervalMonths")
                        .value(hasItem(3)));

        UUID publicId = UUID.randomUUID();
        when(fileSearchPort.findActiveFileById(901L)).thenReturn(Optional.of(
                new CompanyFileSearchPort.CompanyFileMetadata(
                        901L,
                        publicId,
                        "사업자등록증.pdf",
                        "pdf",
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        ));

        mockMvc.perform(put("/api/submission-common-documents/BUSINESS_REGISTRATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":901,"issuedAt":null,"expiresAt":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(901))
                .andExpect(jsonPath("$.filePublicId").value(publicId.toString()))
                .andExpect(jsonPath("$.originalFilename").value("사업자등록증.pdf"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.statusDisplayName").value("사용 가능"))
                .andExpect(jsonPath("$.storagePath").doesNotExist());
    }
}
