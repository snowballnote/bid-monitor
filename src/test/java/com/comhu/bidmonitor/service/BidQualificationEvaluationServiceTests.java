package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidQualificationEvaluationServiceTests {

    private static final Set<String> ALLOWED_LICENSE_CODES = Set.of("6146", "1468");

    private final BidQualificationEvaluationService service =
            new BidQualificationEvaluationService(new BidAwardMethodClassifier());

    @Test
    void informationSystemSupervisionAndPrivacyImpactAssessmentUseSameCompanyRules() {
        BidQualificationDto supervision = eligibleNotice("정보시스템 감리 용역");
        BidQualificationDto privacy = eligibleNotice("개인정보 영향평가 용역");

        service.evaluate(supervision, ALLOWED_LICENSE_CODES);
        service.evaluate(privacy, ALLOWED_LICENSE_CODES);

        assertEquals("검토대상", supervision.getReviewStatus());
        assertEquals(supervision.getReviewStatus(), privacy.getReviewStatus());
        assertEquals(supervision.getReviewReason(), privacy.getReviewReason());
    }

    @Test
    void clearlyOtherAwardMethodIsExcluded() {
        BidQualificationDto notice = eligibleNotice("비관련 일반 용역");
        notice.setSucsfbidMthdNm("최저가낙찰제");
        notice.setSucsfbidMthdCd("낙030002");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("제외", notice.getReviewStatus());
        assertEquals("낙찰방법이 적격심사 또는 소액수의견적 대상이 아님", notice.getReviewReason());
    }

    @Test
    void negotiatedProposalSubmissionNoticeRemainsExcludedByExistingAwardRule() {
        BidQualificationDto notice = eligibleNotice("정보시스템 제안서 제출 용역");
        notice.setSucsfbidMthdNm("협상에 의한 계약");
        notice.setSucsfbidMthdCd("낙030005");
        notice.setAttachments(List.of(analyzedAttachmentWithRequiredDocument("제안서 원본 1부")));

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("제외", notice.getReviewStatus());
        assertEquals("협상에 의한 계약으로 대상 제외", notice.getReviewReason());
    }

    @Test
    void priceCenteredSmallAmountEstimateWithoutProposalRemainsReviewTarget() {
        BidQualificationDto notice = eligibleNotice("정보시스템 감리 견적 공고");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("SMALL_AMOUNT_ESTIMATE", notice.getAwardMethodCategory());
        assertEquals("검토대상", notice.getReviewStatus());
    }

    @Test
    void missingQualificationFieldsRequireConfirmationInsteadOfPassingOrFailing() {
        BidQualificationDto notice = new BidQualificationDto();
        notice.setBidNtceNm("정보시스템 감리 용역");
        notice.setSucsfbidMthdNm("적격심사제");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("추가확인필요", notice.getReviewStatus());
        assertTrue(notice.getReviewReason().contains("면허조건 정보 확인 필요"));
        assertTrue(notice.getReviewReason().contains("지역제한 조건 확인 필요"));
    }

    @Test
    void missingAwardMethodInformationRemainsUnknownAndRequiresConfirmation() {
        BidQualificationDto notice = new BidQualificationDto();

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("UNKNOWN", notice.getAwardMethodCategory());
        assertEquals("추가확인필요", notice.getReviewStatus());
        assertEquals("낙찰방법이 검토대상 기준에 해당하는지 확인 필요", notice.getReviewReason());
    }

    @Test
    void existingExternalCheckResultIsPreserved() {
        BidQualificationDto notice = eligibleNotice("정보시스템 감리 용역");
        notice.setExternalCheckStatus("REQUIRED");
        notice.setExternalSiteCheckRequired(true);
        notice.setExternalSiteUrls(List.of("https://public.example/notice"));
        notice.setExternalCheckReason("외부 제출 확인 필요");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("REQUIRED", notice.getExternalCheckStatus());
        assertEquals(Boolean.TRUE, notice.getExternalSiteCheckRequired());
        assertEquals(List.of("https://public.example/notice"), notice.getExternalSiteUrls());
        assertEquals("외부 제출 확인 필요", notice.getExternalCheckReason());
    }

    @Test
    void absentExternalCheckEvidenceRemainsUnknown() {
        BidQualificationDto notice = eligibleNotice("정보시스템 감리 용역");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("UNKNOWN", notice.getExternalCheckStatus());
        assertNull(notice.getExternalSiteCheckRequired());
    }

    @Test
    void publishedPqTpAndPerformanceConditionsRequireConfirmation() {
        BidQualificationDto notice = eligibleNotice("정보시스템 감리 용역");
        notice.setArsltCmptYn("Y");
        notice.setPqEvalYn("Y");
        notice.setTpEvalYn("Y");

        service.evaluate(notice, ALLOWED_LICENSE_CODES);

        assertEquals("추가확인필요", notice.getReviewStatus());
        assertTrue(notice.getReviewReason().contains("실적경쟁 조건 확인 필요"));
        assertTrue(notice.getReviewReason().contains("PQ심사 조건 확인 필요"));
        assertTrue(notice.getReviewReason().contains("TP심사 조건 확인 필요"));
    }

    @Test
    void failedAttachmentAnalysisRemainsUnknown() {
        BidQualificationDto notice = eligibleNotice("정보시스템 감리 용역");
        BidAttachmentDto failed = new BidAttachmentDto();
        failed.setFileName("공고문.hwp");
        failed.setAnalysisStatus("FAILED");

        service.evaluateExternalCheck(notice, List.of(failed));

        assertEquals("UNKNOWN", notice.getExternalCheckStatus());
        assertNull(notice.getExternalSiteCheckRequired());
        assertTrue(notice.getExternalCheckReason().contains("공고문.hwp"));
    }

    private BidQualificationDto eligibleNotice(String title) {
        BidQualificationDto notice = new BidQualificationDto();
        notice.setBidNtceNm(title);
        notice.setSucsfbidMthdNm("소액수의견적");
        notice.setSucsfbidMthdCd("낙030029");
        notice.setParticipationRegion("제한없음");
        notice.setArsltCmptYn("N");
        notice.setPqEvalYn("N");
        notice.setTpEvalYn("N");
        notice.setLicenseGroups(List.of(new LicenseRequirementGroup("1", List.of(
                new LicenseRequirement("1", "6146", "정보시스템 감리법인", "정보시스템 감리법인/6146")
        ))));
        return notice;
    }

    private BidAttachmentDto analyzedAttachmentWithRequiredDocument(String document) {
        BidDocumentAnalysisDto analysis = new BidDocumentAnalysisDto();
        analysis.setAnalysisStatus("ANALYZED");
        analysis.setRequiredDocuments(List.of(document));
        BidAttachmentDto attachment = new BidAttachmentDto();
        attachment.setAnalysisStatus("ANALYZED");
        attachment.setDocumentAnalysis(analysis);
        return attachment;
    }
}
