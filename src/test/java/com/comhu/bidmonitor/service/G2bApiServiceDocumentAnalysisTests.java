package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceDocumentAnalysisTests {

    private final G2bApiService service = new G2bApiService();

    @Test
    void extractsBidReviewItemsWithoutSummarizingSourceText() {
        String documentText = """
                1. 입찰 참가자격
                ㅇ 정보시스템 감리법인으로 등록된 업체
                ㅇ 최근 3년 이내 감리 실적을 보유한 업체
                ㅇ 최근 3년 이내 감리 실적을 보유한 업체
                2. 제출서류
                구분 ･ 입찰참가신청서 1부 ･ 사업자등록증 사본 1부
                3. 제출 및 문의처
                ㅇ 제출처 : 서울시청 정보화담당관
                ㅇ 접수기한 : 2026. 8. 31. 17:00까지 우편 또는 직접 제출
                4. 공동수급
                ㅇ 공동수급협정서는 입찰마감 전까지 제출해야 함
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals("ANALYZED", result.getAnalysisStatus());
        assertEquals(2, result.getQualificationRequirements().size());
        assertEquals("ㅇ 정보시스템 감리법인으로 등록된 업체",
                result.getQualificationRequirements().getFirst());
        assertEquals(2, result.getRequiredDocuments().size());
        assertEquals("입찰참가신청서 1부", result.getRequiredDocuments().getFirst());
        assertTrue(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("서울시청")));
        assertTrue(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("직접 제출")));
        assertTrue(result.getSubmissionDeadlines().stream().anyMatch(value -> value.contains("17:00")));
        assertTrue(result.getJointContractRequirements().stream()
                .anyMatch(value -> value.contains("공동수급협정서")));
    }

    @Test
    void distinguishesNoDetectionFromAnalysisFailure() {
        BidDocumentAnalysisDto noDetection = service.analyzeBidDocumentText("일반 안내 문장입니다.");
        BidDocumentAnalysisDto failure = service.analyzeBidDocumentText("   ");

        assertEquals("ANALYZED", noDetection.getAnalysisStatus());
        assertTrue(noDetection.getRequiredDocuments().isEmpty());
        assertTrue(noDetection.getAnalysisNote().contains("탐지되지 않음"));
        assertEquals("FAILED", failure.getAnalysisStatus());
        assertTrue(failure.getAnalysisNote().contains("본문 텍스트가 없어"));
    }
}
