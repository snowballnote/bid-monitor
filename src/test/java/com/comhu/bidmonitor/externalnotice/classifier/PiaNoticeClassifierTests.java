package com.comhu.bidmonitor.externalnotice.classifier;

import com.comhu.bidmonitor.externalnotice.domain.CollectedNotice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiaNoticeClassifierTests {

    private final PiaNoticeClassifier classifier = new PiaNoticeClassifier();

    @Test
    void classifiesProfessionalEducationNoticeAsPiaRelated() {
        PiaClassificationResult result = classify("2026년 개인정보 영향평가 전문교육 안내", "교육 일정입니다.");

        assertTrue(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().contains("개인정보 영향평가"));
        assertTrue(result.getMatchedKeywords().contains("전문교육"));
        assertTrue(result.getReason().startsWith("제목에서"));
    }

    @Test
    void classifiesContinuingEducationApplicationNoticeAsPiaRelated() {
        PiaClassificationResult result = classify("개인정보 영향평가 계속교육 신청 안내", "");

        assertTrue(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().contains("계속교육"));
        assertTrue(result.getMatchedKeywords().contains("신청"));
    }

    @Test
    void classifiesNoticeAsPiaRelatedWhenKeywordsExistOnlyInBody() {
        PiaClassificationResult result = classify("교육 대상자 안내", "PIA 전문인력 대상 공지입니다.");

        assertTrue(result.isPiaRelated());
        assertEquals("PIA", result.getMatchedKeywords().getFirst());
        assertTrue(result.getMatchedKeywords().contains("전문인력"));
        assertTrue(result.getReason().startsWith("본문에서"));
    }

    @Test
    void classifiesPrivacyPortalMaintenanceNoticeAsGeneral() {
        PiaClassificationResult result = classify("개인정보 포털 시스템 점검 안내", "서비스가 일시 중단됩니다.");

        assertFalse(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().isEmpty());
    }

    @Test
    void classifiesIdentityVerificationNoticeAsGeneral() {
        PiaClassificationResult result = classify("본인확인 서비스 안내", "휴대전화 본인확인 방식이 변경됩니다.");

        assertFalse(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().isEmpty());
    }

    @Test
    void ignoresWhitespaceAndLineBreaksInsideKoreanKeyword() {
        PiaClassificationResult result = classify("개인정보   \n 영향평가 안내", "");

        assertTrue(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().contains("개인정보 영향평가"));
    }

    @Test
    void ignoresCaseAndWhitespaceInsidePiaAbbreviation() {
        PiaClassificationResult result = classify("교육 안내", "p  i\n a 전문인력 대상입니다.");

        assertTrue(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().contains("PIA"));
    }

    @Test
    void doesNotClassifyNoticeFromGenericSupportKeywordsAlone() {
        PiaClassificationResult result = classify("자격 신청 접수 마감 안내", "유효기간 갱신 절차입니다.");

        assertFalse(result.isPiaRelated());
        assertTrue(result.getMatchedKeywords().contains("자격"));
        assertTrue(result.getReason().contains("보조 키워드만"));
    }

    private PiaClassificationResult classify(String title, String body) {
        CollectedNotice notice = CollectedNotice.builder()
                .title(title)
                .body(body)
                .build();
        return classifier.classify(notice);
    }
}
