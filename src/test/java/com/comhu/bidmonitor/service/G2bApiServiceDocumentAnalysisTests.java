package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void excludesPostContractAndReportNoiseWhileKeepingBidSubmissionInformation() {
        String documentText = """
                3. 입찰 참가자격
                가. 정보시스템 감리법인(업종코드 6146)으로 입찰참가 등록한 업체
                나. 중소기업 확인서를 보유한 소프트웨어사업자
                4. 입찰보증금
                낙찰자는 계약체결 시 인지세와 납세증명서를 제출하여야 합니다.
                청렴계약 및 동반성장 지원사업 안내입니다.
                5. 제출서류
                · 1468 컴퓨터관련서비스사업
                · 1426 패키지 소프트웨어 개발공급사업
                · 사업자등록증 사본 1부
                · 경쟁입찰참가자격등록증 1부
                · 제안서 원본 및 USB 1부
                · 계약상대자는 용역수행 결과물을 첨부하여 검사를 요청해야 함
                담당자 이메일(bid@example.com)을 통한 사전접수
                감사실 이메일(audit@example.com)을 통하여 비리 신고
                전자입찰서 접수 마감일시 : 2026. 8. 20. 14:00
                입찰마감일 전일까지 업종코드 6146으로 참가 등록한 업체
                ▢ 공동수급 및 하도급 제한
                ◦ 본 사업은 공동수급 허용하나 하도급은 불허함
                ◦ (공동수급) 본 사업은 공동이행방식 공동계약 허용
                ◦ 공동수급체 대표사는 업종코드 1468로 신고한 사업자이어야 함
                ◦ 공동수급체는 3개 이하, 구성원별 최소지분율 10% 이상이며 중복 구성은 불가함
                ◦ 공동수급 참여지분율 평가점수 배점표
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(2, result.getQualificationRequirements().size());
        assertFalse(result.getQualificationRequirements().stream()
                .anyMatch(value -> value.contains("입찰보증금") || value.contains("인지세")
                        || value.contains("납세증명") || value.contains("동반성장")));
        assertEquals(4, result.getRequiredDocuments().size());
        assertFalse(result.getRequiredDocuments().stream()
                .anyMatch(value -> value.contains("1468 컴퓨터") || value.contains("1426 패키지")
                        || value.contains("검사를 요청")));
        assertEquals(1, result.getSubmissionMethods().size());
        assertTrue(result.getSubmissionMethods().getFirst().contains("사전접수"));
        assertFalse(result.getSubmissionMethods().stream()
                .anyMatch(value -> value.contains("감사실") || value.contains("신고")));
        assertEquals(1, result.getSubmissionDeadlines().size());
        assertTrue(result.getSubmissionDeadlines().getFirst().contains("2026. 8. 20. 14:00"));
        assertEquals(4, result.getJointContractRequirements().size());
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("공동이행방식")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("대표사")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("3개 이하")));
        assertFalse(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("평가점수")));
    }

    @Test
    void removesWhitespaceOnlyDuplicatesWithoutRewritingOriginalText() {
        String documentText = """
                1. 입찰참가자격
                ㅇ 정보시스템 감리법인으로 입찰 참가 등록한 업체
                ㅇ 정보시스템 감리법인으로 입찰참가 등록한 업체
                2. 기타사항
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(1, result.getQualificationRequirements().size());
        assertEquals("ㅇ 정보시스템 감리법인으로 입찰 참가 등록한 업체",
                result.getQualificationRequirements().getFirst());
    }

    @Test
    void recognizesPdfTableHeadingAndRejectsLicenseCodesAsDocuments() {
        String documentText = """
                구분제출서류 (사본의 경우 사실과 다르지 않음 표기)입찰서
                기타1- 사업자등록증 사본 1부
                · 1468 컴퓨터관련서비스사업
                · 1426 패키지 소프트웨어 개발공급사업
                7. 평가방법
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(1, result.getRequiredDocuments().size());
        assertTrue(result.getRequiredDocuments().getFirst().contains("사업자등록증"));
    }

    @Test
    void keepsElectronicBidPeriodButRejectsPhoneNumberAndOpeningTimeAsDeadlines() {
        String documentText = """
                전자입찰서 제출기간
                시작일시 : 2026. 7. 20. 10:00
                마감일시 : 2026. 7. 22. 12:00
                개찰일시 : 2026. 7. 22. 13:30
                입찰서 제출마감 문의 : 1588-0800
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(2, result.getSubmissionDeadlines().size());
        assertTrue(result.getSubmissionDeadlines().stream().anyMatch(value -> value.contains("7. 20. 10:00")));
        assertTrue(result.getSubmissionDeadlines().stream().anyMatch(value -> value.contains("7. 22. 12:00")));
        assertFalse(result.getSubmissionDeadlines().stream().anyMatch(value -> value.contains("13:30")));
        assertFalse(result.getSubmissionDeadlines().stream().anyMatch(value -> value.contains("1588-0800")));
    }

    @Test
    void recognizesBidDeadlineKeywordsSplitByPdfTableSpacing() {
        String documentText = """
                전 자 입 찰 서 제 출 기 간 : 2026. 7. 20. 10:00 ~ 2026. 7. 22. 12:00
                정 부 조 달 콜 센 터 : 1588-0800
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(1, result.getSubmissionDeadlines().size());
        assertTrue(result.getSubmissionDeadlines().getFirst().contains("2026. 7. 20."));
        assertTrue(result.getSubmissionDeadlines().getFirst().contains("2026. 7. 22."));
        assertFalse(result.getSubmissionDeadlines().getFirst().contains("1588-0800"));
    }

    @Test
    void keepsOnlyActualBidSubmissionMethods() {
        String documentText = """
                전자입찰서는 나라장터를 통하여 전자제출합니다.
                제안서는 담당자 이메일을 통한 사전접수 후 제출합니다.
                제안서는 방문 또는 우편 제출합니다.
                입찰보증금 지급각서를 전자 제출한 것으로 갈음합니다.
                계약상대자는 과업 산출물을 제출처에 납품합니다.
                하도급지킴이 확약서를 온라인으로 제출합니다.
                감사실 이메일로 비리 신고를 접수합니다.
                제안서 제출 이후 과업 수행 결과물
                제출처 : 안전관리실
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(3, result.getSubmissionMethods().size());
        assertTrue(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("나라장터")));
        assertTrue(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("사전접수")));
        assertTrue(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("방문 또는 우편")));
        assertFalse(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("보증금")));
        assertFalse(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("산출물")));
        assertFalse(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("하도급")));
        assertFalse(result.getSubmissionMethods().stream().anyMatch(value -> value.contains("안전관리실")));
    }

    @Test
    void splitsMergedDocumentTableCellsIntoIndividualDocuments() {
        String documentText = """
                5. 제출서류
                구분 제출서류
                사업자등록증 사본 1부 법인등기부등본 원본 1부 인감증명서 1부 사용인감계 1부
                경쟁입찰참가자격등록증 1부 중소기업확인서 1부 직접생산확인증명서 1부
                1468 컴퓨터관련서비스사업
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(7, result.getRequiredDocuments().size());
        assertTrue(result.getRequiredDocuments().stream().anyMatch(value -> value.startsWith("사업자등록증")));
        assertTrue(result.getRequiredDocuments().stream().anyMatch(value -> value.startsWith("법인등기부등본")));
        assertTrue(result.getRequiredDocuments().stream().anyMatch(value -> value.startsWith("사용인감계")));
        assertTrue(result.getRequiredDocuments().stream().anyMatch(value -> value.startsWith("직접생산확인증명서")));
        assertFalse(result.getRequiredDocuments().stream().anyMatch(value -> value.contains("구분 제출서류")));
        assertFalse(result.getRequiredDocuments().stream().anyMatch(value -> value.contains("1468 컴퓨터")));
    }

    @Test
    void doesNotSplitDocumentNamesMentionedInsideParentheses() {
        String documentText = """
                5. 제출서류
                법인등기부등본(개인사업자는 사업자등록증만 제출) 1부
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(1, result.getRequiredDocuments().size());
        assertTrue(result.getRequiredDocuments().getFirst().contains("개인사업자는 사업자등록증만 제출"));
    }

    @Test
    void keepsFirstUnlabeledDeadlineValueAfterHwpxTableHeading() {
        String documentText = """
                전자입찰서 제출 마감일시
                2026/08/14 10:00
                2026/08/14 11:00
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(1, result.getSubmissionDeadlines().size());
        assertTrue(result.getSubmissionDeadlines().getFirst().contains("10:00"));
        assertFalse(result.getSubmissionDeadlines().getFirst().contains("11:00"));
    }

    @Test
    void keepsRepresentativeShareAndMemberRulesInsideJointContractSection() {
        String documentText = """
                4. 공동수급
                가. 공동이행방식으로 허용합니다.
                나. 대표사는 업종코드 1468로 등록하여야 합니다.
                다. 구성원은 3개사 이하이며 최소지분율은 10% 이상이어야 합니다.
                라. 중복 구성은 금지하고 낙찰 후 구성원을 변경할 수 없습니다.
                마. 공동수급협정서는 입찰마감 전까지 제출합니다.
                5. 입찰보증금
                공동수급체의 보증금은 발주기관에 귀속됩니다.
                공동수급 구성원 전원은 상호 및 대표자가 다른 경우 변경등록하여야 합니다.
                """;

        BidDocumentAnalysisDto result = service.analyzeBidDocumentText(documentText);

        assertEquals(5, result.getJointContractRequirements().size());
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("공동이행")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("대표사")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("최소지분율")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("변경할 수 없습니다")));
        assertTrue(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("협정서")));
        assertFalse(result.getJointContractRequirements().stream().anyMatch(value -> value.contains("귀속")));
    }
}
