package com.comhu.bidmonitor.externalnotice.api;

import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeDetailResponse;
import com.comhu.bidmonitor.externalnotice.api.dto.ExternalNoticeListResponse;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionService;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeAttachment;
import com.comhu.bidmonitor.externalnotice.persistence.ExternalNoticeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external-notice-api;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
class ExternalNoticeControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExternalNoticeRepository repository;

    @MockitoBean
    private ExternalNoticeCollectionService collectionService;

    @Test
    void collectsManuallyAndReturnsNewThenUnchangedCounts() throws Exception {
        ExternalNoticeCollectionResult firstResult = collectionResult(2, 2, 0);
        ExternalNoticeCollectionResult secondResult = collectionResult(2, 0, 2);
        when(collectionService.runCollection()).thenReturn(firstResult, secondResult);

        mockMvc.perform(post("/api/external-notices/collect"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.collectedCount").value(2))
                .andExpect(jsonPath("$.newCount").value(2))
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.unchangedCount").value(0))
                .andExpect(jsonPath("$.piaRelatedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.failures").isEmpty());

        mockMvc.perform(post("/api/external-notices/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newCount").value(0))
                .andExpect(jsonPath("$.unchangedCount").value(2));
    }

    @Test
    void returnsNoticeListLatestFirstWithoutDetailOnlyFields() throws Exception {
        repository.save(notice("400", LocalDate.of(2026, 8, 1), true));
        repository.save(notice("401", LocalDate.of(2026, 9, 1), false));

        mockMvc.perform(get("/api/external-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value(externalId("401")))
                .andExpect(jsonPath("$[1].externalId").value(externalId("400")))
                .andExpect(jsonPath("$[0].title").value("외부공지 401"))
                .andExpect(jsonPath("$[0].publishedDate").value("2026-09-01"))
                .andExpect(jsonPath("$[0].matchedKeywords").isArray())
                .andExpect(jsonPath("$[0].detailUrl").exists())
                .andExpect(jsonPath("$[0].body").doesNotExist())
                .andExpect(jsonPath("$[0].fingerprint").doesNotExist())
                .andExpect(jsonPath("$[0].attachments").doesNotExist());
    }

    @Test
    void filtersNoticeListByPiaRelated() throws Exception {
        repository.save(notice("402", LocalDate.of(2026, 9, 1), true));
        repository.save(notice("403", LocalDate.of(2026, 9, 2), false));

        mockMvc.perform(get("/api/external-notices").param("piaRelated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalId").value(externalId("402")))
                .andExpect(jsonPath("$[0].piaRelated").value(true));
    }

    @Test
    void returnsNoticeDetailWithBodyFingerprintAndAttachments() throws Exception {
        ExternalNotice saved = repository.save(notice("404", LocalDate.of(2026, 9, 1), true));

        mockMvc.perform(get("/api/external-notices/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.externalId").value(externalId("404")))
                .andExpect(jsonPath("$.body").value("외부공지 상세 본문 404"))
                .andExpect(jsonPath("$.classificationReason").value("PIA 핵심 키워드 발견"))
                .andExpect(jsonPath("$.fingerprint").value("a".repeat(64)))
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andExpect(jsonPath("$.attachments[0].fileName").value("안내문.hwp"))
                .andExpect(jsonPath("$.attachments[0].fileUrl").value("https://example.com/files/guide"));
    }

    @Test
    void returnsClearNotFoundResponseForMissingId() throws Exception {
        mockMvc.perform(get("/api/external-notices/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("외부공지를 찾을 수 없습니다: 999999"));
    }

    @Test
    void controllerMethodSignaturesExposeApiDtosInsteadOfPersistenceModel() throws Exception {
        Method listMethod = ExternalNoticeController.class.getDeclaredMethod("getNotices", Boolean.class);
        Method detailMethod = ExternalNoticeController.class.getDeclaredMethod("getNotice", Long.class);

        assertEquals(
                "java.util.List<" + ExternalNoticeListResponse.class.getName() + ">",
                listMethod.getGenericReturnType().getTypeName()
        );
        assertEquals(ExternalNoticeDetailResponse.class, detailMethod.getReturnType());
    }

    private ExternalNoticeCollectionResult collectionResult(int collected, int newCount, int unchangedCount) {
        return ExternalNoticeCollectionResult.builder()
                .collectedCount(collected)
                .newCount(newCount)
                .updatedCount(0)
                .unchangedCount(unchangedCount)
                .piaRelatedCount(1)
                .failedCount(0)
                .noticeResult(NoticeChangeResult.builder()
                        .externalId(externalId("api"))
                        .changeType(newCount > 0 ? NoticeChangeType.NEW : NoticeChangeType.UNCHANGED)
                        .noticeId(1L)
                        .currentFingerprint("a".repeat(64))
                        .build())
                .build();
    }

    private ExternalNotice notice(String sourceNoticeId, LocalDate publishedDate, boolean piaRelated) {
        return ExternalNotice.builder()
                .sourceCode("PRIVACY_PORTAL")
                .externalId(externalId(sourceNoticeId))
                .sourceNoticeId(sourceNoticeId)
                .title("외부공지 " + sourceNoticeId)
                .publishedDate(publishedDate)
                .detailUrl("https://example.com/notices/" + sourceNoticeId)
                .body("외부공지 상세 본문 " + sourceNoticeId)
                .piaRelated(piaRelated)
                .matchedKeywords(piaRelated ? List.of("PIA", "전문교육") : List.of())
                .classificationReason(piaRelated ? "PIA 핵심 키워드 발견" : "관련 키워드 없음")
                .fingerprint("a".repeat(64))
                .firstSeenAt(Instant.parse("2026-09-01T01:00:00Z"))
                .lastSeenAt(Instant.parse("2026-09-01T02:00:00Z"))
                .attachment(ExternalNoticeAttachment.builder()
                        .fileName("안내문.hwp")
                        .fileUrl("https://example.com/files/guide")
                        .build())
                .attachment(ExternalNoticeAttachment.builder()
                        .fileName("신청서.pdf")
                        .fileUrl("https://example.com/files/form")
                        .build())
                .build();
    }

    private String externalId(String sourceNoticeId) {
        return "PRIVACY_PORTAL:BOARD:" + sourceNoticeId;
    }
}
