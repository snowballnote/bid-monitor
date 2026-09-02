package com.comhu.bidmonitor.classifier;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BidAwardMethodClassifierTests {

    private final BidAwardMethodClassifier classifier = new BidAwardMethodClassifier();

    @Test
    void structuredDetailQualificationReviewSystemIsConfirmed() {
        BidQualificationDto notice = noticeWithMethod("적격심사제", "");

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.QUALIFICATION_REVIEW, result.category());
        assertEquals(BidAwardMethodStatus.CONFIRMED, result.status());
        assertEquals(BidAwardMethodSource.STRUCTURED_DETAIL, result.source());
    }

    @Test
    void structuredDetailQualificationReviewIsConfirmed() {
        BidQualificationDto notice = noticeWithMethod("적격심사", "");

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.QUALIFICATION_REVIEW, result.category());
        assertEquals(BidAwardMethodStatus.CONFIRMED, result.status());
    }

    @Test
    void applicationStandardCanConfirmQualificationReview() {
        BidQualificationDto notice = noticeWithMethod("기타", "");
        notice.setSucsfbidMthdAppStd("낙찰자선정방법 : 적격심사제");

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.QUALIFICATION_REVIEW, result.category());
        assertEquals(BidAwardMethodStatus.CONFIRMED, result.status());
        assertEquals(BidAwardMethodSource.STRUCTURED_DETAIL, result.source());
    }

    @Test
    void clearAttachmentContextIsLikelyRatherThanConfirmed() {
        BidQualificationDto notice = noticeWithMethod("", "");
        notice.setAttachments(List.of(analyzedAttachment("낙찰자선정방법 : 적격심사제")));

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.QUALIFICATION_REVIEW, result.category());
        assertEquals(BidAwardMethodStatus.LIKELY, result.status());
        assertEquals(BidAwardMethodSource.ATTACHMENT_DOCUMENT, result.source());
    }

    @Test
    void structuredOtherMethodTakesPriorityOverAttachmentText() {
        BidQualificationDto notice = noticeWithMethod("최저가낙찰제", "낙030002");
        notice.setAttachments(List.of(analyzedAttachment("낙찰자선정방법 : 적격심사제")));

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.OTHER, result.category());
        assertEquals(BidAwardMethodStatus.NOT_DETECTED, result.status());
        assertEquals(BidAwardMethodSource.STRUCTURED_DETAIL, result.source());
    }

    @Test
    void genericReviewWordDoesNotIndicateQualificationReview() {
        BidQualificationDto notice = noticeWithMethod("", "");
        notice.setAttachments(List.of(analyzedAttachment("제안서 심사 결과는 별도 통보합니다.")));

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.UNKNOWN, result.category());
        assertEquals(BidAwardMethodStatus.UNKNOWN, result.status());
    }

    @Test
    void isolatedQualificationReviewWordInAttachmentIsNotEnough() {
        BidQualificationDto notice = noticeWithMethod("", "");
        notice.setAttachments(List.of(analyzedAttachment("적격심사 관련 자료를 참고하십시오.")));

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.UNKNOWN, result.category());
        assertEquals(BidAwardMethodStatus.UNKNOWN, result.status());
    }

    @Test
    void absentInformationIsUnknown() {
        BidAwardMethodResult result = classifier.classify(new BidQualificationDto());

        assertEquals(BidAwardMethodCategory.UNKNOWN, result.category());
        assertEquals(BidAwardMethodStatus.UNKNOWN, result.status());
        assertEquals(BidAwardMethodSource.NOT_AVAILABLE, result.source());
    }

    @Test
    void existingSmallAmountEstimateClassificationIsPreserved() {
        BidQualificationDto notice = noticeWithMethod("소액수의견적", "낙030029");

        BidAwardMethodResult result = classifier.classify(notice);

        assertEquals(BidAwardMethodCategory.SMALL_AMOUNT_ESTIMATE, result.category());
        assertEquals(BidAwardMethodStatus.CONFIRMED, result.status());
        assertEquals(BidAwardMethodSource.STRUCTURED_DETAIL, result.source());
    }

    private BidQualificationDto noticeWithMethod(String name, String code) {
        BidQualificationDto notice = new BidQualificationDto();
        notice.setSucsfbidMthdNm(name);
        notice.setSucsfbidMthdCd(code);
        return notice;
    }

    private BidAttachmentDto analyzedAttachment(String qualificationText) {
        BidDocumentAnalysisDto analysis = new BidDocumentAnalysisDto();
        analysis.setAnalysisStatus("ANALYZED");
        analysis.setAwardMethodEvidence(List.of(qualificationText));
        BidAttachmentDto attachment = new BidAttachmentDto();
        attachment.setFileName("공고문.pdf");
        attachment.setAnalysisStatus("ANALYZED");
        attachment.setDocumentAnalysis(analysis);
        return attachment;
    }
}
