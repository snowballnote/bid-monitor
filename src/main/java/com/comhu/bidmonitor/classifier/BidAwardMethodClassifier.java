package com.comhu.bidmonitor.classifier;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidDocumentAnalysisDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 나라장터 검색 필터와 독립적으로 상세 필드와 분석된 첨부문서에서 낙찰방법을 판정한다.
 * 구조화 상세정보를 우선하며, 첨부문서는 명확한 적격심사 문맥이 있을 때만 보조 근거로 사용한다.
 */
@Service
public class BidAwardMethodClassifier {

    private static final String QUALIFICATION_REVIEW_CODE = "낙030001";
    private static final String SMALL_AMOUNT_ESTIMATE_CODE = "낙030029";
    private static final String NEGOTIATED_CONTRACT_CODE = "낙030005";

    public BidAwardMethodResult classify(BidQualificationDto notice) {
        String methodName = normalize(notice == null ? null : notice.getSucsfbidMthdNm());
        String methodCode = normalize(notice == null ? null : notice.getSucsfbidMthdCd());
        String applicationStandard = normalize(notice == null ? null : notice.getSucsfbidMthdAppStd());

        if (containsQualificationReview(methodName)) {
            return confirmed(BidAwardMethodCategory.QUALIFICATION_REVIEW,
                    "낙찰방법 필드에서 \"" + display(notice.getSucsfbidMthdNm()) + "\" 확인");
        }
        if (containsSmallAmountEstimate(methodName) || SMALL_AMOUNT_ESTIMATE_CODE.equals(methodCode)) {
            return confirmed(BidAwardMethodCategory.SMALL_AMOUNT_ESTIMATE,
                    "구조화 상세정보에서 소액수의견적 확인");
        }
        if (QUALIFICATION_REVIEW_CODE.equals(methodCode)) {
            return confirmed(BidAwardMethodCategory.QUALIFICATION_REVIEW,
                    "낙찰방법 코드에서 적격심사 코드 " + QUALIFICATION_REVIEW_CODE + " 확인");
        }
        if (NEGOTIATED_CONTRACT_CODE.equals(methodCode) || methodName.contains("협상")) {
            return new BidAwardMethodResult(
                    BidAwardMethodCategory.OTHER,
                    BidAwardMethodStatus.NOT_DETECTED,
                    "구조화 상세정보에서 협상에 의한 계약 확인",
                    BidAwardMethodSource.STRUCTURED_DETAIL
            );
        }
        if (containsQualificationReview(applicationStandard)) {
            return confirmed(BidAwardMethodCategory.QUALIFICATION_REVIEW,
                    "낙찰방법 적용기준에서 \"" + display(notice.getSucsfbidMthdAppStd()) + "\" 확인");
        }

        // 구조화 상세정보가 명시한 다른 방식은 보조 문서의 표현보다 우선한다.
        if (!methodName.isEmpty() || !methodCode.isEmpty() || !applicationStandard.isEmpty()) {
            return new BidAwardMethodResult(
                    BidAwardMethodCategory.OTHER,
                    BidAwardMethodStatus.NOT_DETECTED,
                    "구조화 상세정보에서 적격심사 또는 소액수의견적 근거가 확인되지 않음",
                    BidAwardMethodSource.STRUCTURED_DETAIL
            );
        }

        String attachmentEvidence = findAttachmentEvidence(notice == null ? null : notice.getAttachments());
        if (!attachmentEvidence.isEmpty()) {
            return new BidAwardMethodResult(
                    BidAwardMethodCategory.QUALIFICATION_REVIEW,
                    BidAwardMethodStatus.LIKELY,
                    "첨부문서에서 명확한 적격심사 문맥 확인: " + attachmentEvidence,
                    BidAwardMethodSource.ATTACHMENT_DOCUMENT
            );
        }

        return new BidAwardMethodResult(
                BidAwardMethodCategory.UNKNOWN,
                BidAwardMethodStatus.UNKNOWN,
                "낙찰방법을 판정할 구조화 상세정보 또는 명확한 첨부문서 근거가 없음",
                BidAwardMethodSource.NOT_AVAILABLE
        );
    }

    private BidAwardMethodResult confirmed(BidAwardMethodCategory category, String reason) {
        return new BidAwardMethodResult(
                category,
                BidAwardMethodStatus.CONFIRMED,
                reason,
                BidAwardMethodSource.STRUCTURED_DETAIL
        );
    }

    private String findAttachmentEvidence(List<BidAttachmentDto> attachments) {
        if (attachments == null) {
            return "";
        }
        for (BidAttachmentDto attachment : attachments) {
            if (attachment == null || attachment.getDocumentAnalysis() == null) {
                continue;
            }
            for (String text : analysisTexts(attachment.getDocumentAnalysis())) {
                if (hasClearAttachmentContext(text)) {
                    return display(text);
                }
            }
        }
        return "";
    }

    private List<String> analysisTexts(BidDocumentAnalysisDto analysis) {
        List<String> texts = new ArrayList<>();
        addAll(texts, analysis.getAwardMethodEvidence());
        addAll(texts, analysis.getQualificationRequirements());
        addAll(texts, analysis.getRequiredDocuments());
        addAll(texts, analysis.getSubmissionMethods());
        addAll(texts, analysis.getSubmissionDeadlines());
        addAll(texts, analysis.getJointContractRequirements());
        return texts;
    }

    private void addAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }

    private boolean hasClearAttachmentContext(String value) {
        String normalized = normalize(value);
        if (!containsQualificationReview(normalized)) {
            return false;
        }
        return normalized.contains("낙찰자선정방법")
                || normalized.contains("낙찰방법")
                || normalized.contains("적격심사제")
                || normalized.contains("적격심사대상")
                || normalized.contains("적격심사세부기준")
                || normalized.contains("적격심사기준");
    }

    private boolean containsQualificationReview(String value) {
        return value.contains("적격심사") && !value.contains("부적격심사");
    }

    private boolean containsSmallAmountEstimate(String value) {
        return value.contains("소액수의견적") || value.contains("소액수의");
    }

    /** 공백·줄바꿈 차이와 영문 대소문자를 제거해 동일한 표현으로 비교한다. */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String display(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}
