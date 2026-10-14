package com.comhu.bidmonitor.classifier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BidRelevanceClassifierTests {

    private final BidRelevanceClassifier classifier = new BidRelevanceClassifier();

    @ParameterizedTest
    @MethodSource("cases")
    void classifiesNoticeTitle(String title, BidRelevance expected) {
        assertEquals(expected, classifier.classify(title));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("정보시스템 감리 용역", BidRelevance.RELEVANT),
                Arguments.of("정보화 감리 용역", BidRelevance.RELEVANT),
                Arguments.of("개인정보영향평가 수행 용역", BidRelevance.RELEVANT),
                Arguments.of("개인정보 보호 PIA 수행 용역", BidRelevance.RELEVANT),
                Arguments.of("대기자 통합시스템 구축 1차 감리 용역", BidRelevance.RELEVANT),
                Arguments.of("IT 감리 용역", BidRelevance.RELEVANT),
                Arguments.of("시스템 유지관리 용역", BidRelevance.UNKNOWN),
                Arguments.of("클라우드 운영 용역", BidRelevance.RELEVANT),
                Arguments.of("건설공사 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("건축 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("전기 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("소방 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("정보통신공사 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("토목 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("건설사업관리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("건설폐기물 처리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("건설폐기물(소각) 처리용역", BidRelevance.IRRELEVANT),
                Arguments.of("건설폐기물(파쇄,매립) 위탁처리용역", BidRelevance.IRRELEVANT),
                Arguments.of("석면 해체/제거 작업 감리 용역", BidRelevance.IRRELEVANT),
                Arguments.of("석면해체·제거작업 감리용역", BidRelevance.IRRELEVANT),
                Arguments.of("교통안전시설 기술검토용역", BidRelevance.IRRELEVANT),
                Arguments.of("지하안전평가 용역", BidRelevance.IRRELEVANT),
                Arguments.of("교통정보시스템 구축 용역", BidRelevance.RELEVANT),
                Arguments.of("석면 정보관리시스템 구축 용역", BidRelevance.UNKNOWN),
                Arguments.of("강정고령보 다이크 거석 제거 공사", BidRelevance.UNKNOWN),
                Arguments.of("(여성)용담댐 좌안생태공원 화장실 옥상방수 개선공사", BidRelevance.UNKNOWN),
                Arguments.of("부안권지사 전기차 충전설비 인프라 구축 공사", BidRelevance.UNKNOWN),
                Arguments.of("시화조력 수차발전기 친환경 유압작동유 교체 용역", BidRelevance.UNKNOWN),
                Arguments.of("논산시 현대화사업 유지관리시스템 기능 및 실시설계 용역", BidRelevance.UNKNOWN),
                Arguments.of("상수도 유지관리시스템 개선 용역", BidRelevance.UNKNOWN),
                Arguments.of("시설물 유지관리시스템 구축 용역", BidRelevance.UNKNOWN),
                Arguments.of("정보시스템 유지관리 용역", BidRelevance.RELEVANT),
                Arguments.of("전산시스템 유지관리 용역", BidRelevance.RELEVANT),
                Arguments.of("소프트웨어 유지관리 용역", BidRelevance.RELEVANT),
                Arguments.of("정보화 시스템 구축 용역", BidRelevance.RELEVANT),
                Arguments.of("플랫폼 구축 용역", BidRelevance.RELEVANT),
                Arguments.of("시스템 구축 감리 용역", BidRelevance.RELEVANT),
                Arguments.of("감리 용역", BidRelevance.RELEVANT),
                Arguments.of("정기 점검 용역", BidRelevance.UNKNOWN),
                Arguments.of(null, BidRelevance.UNKNOWN)
        );
    }
}
