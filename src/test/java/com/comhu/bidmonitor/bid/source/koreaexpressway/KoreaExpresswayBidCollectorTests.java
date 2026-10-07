package com.comhu.bidmonitor.bid.source.koreaexpressway;

import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.BidQualificationEvaluationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KoreaExpresswayBidCollectorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KoreaExpresswayBidCollector collector =
            new KoreaExpresswayBidCollector("https://ebid.ex.co.kr");

    @Test
    void fixtureCarriesPublishedQualificationInputsToCommonEvaluation() throws Exception {
        BidQualificationDto result = collector.toQualification(
                fixture("list-item.json"), fixture("detail.json")
        );

        assertEquals("KOREA_EXPRESSWAY", result.getSourceCode());
        assertEquals("18dfabd2-78d6-4516-9df0-719509258e03", result.getSourceNoticeId());
        assertEquals("1", result.getRevision());
        assertEquals("202608222-00", result.getBidNtceNo());
        assertEquals("AI기술을 활용한 통행료정보시스템 고도화 감리용역", result.getBidNtceNm());
        assertEquals("한국도로공사", result.getNtceInsttNm());
        assertEquals("2026-08-27 10:00", result.getBidNtceDt());
        assertEquals("2026-09-04 10:00", result.getBidClseDt());
        assertEquals("134827834", result.getAsignBdgtAmt());
        assertEquals(
                "https://ebid.ex.co.kr/default.do?menuId=NPRO12001"
                        + "&noti_id=18dfabd2-78d6-4516-9df0-719509258e03"
                        + "&noti_cont_id=203a2dd1-1f77-4a20-9796-f2366708733f"
                        + "&noti_no=202608222&bid_no=1&bid_rev=1",
                result.getBidNtceDtlUrl()
        );
        assertEquals(result.getBidNtceDtlUrl(), result.getDetailUrl());
        assertEquals("적격심사제", result.getSucsfbidMthdNm());
        assertEquals("한국도로공사 용역 적격심사 세부기준", result.getSucsfbidMthdAppStd());
        assertEquals(List.of("6146", "1468"), result.getLicenseGroups().getFirst().getRequirements().stream()
                .map(requirement -> requirement.getLicenseCode()).toList());
        assertEquals("", result.getParticipationRegion());
        assertEquals("Y", result.getPqEvalYn());
        assertEquals("", result.getTpEvalYn());

        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(result, Set.of("6146", "1468"));

        assertEquals("QUALIFICATION_REVIEW", result.getAwardMethodCategory());
        assertEquals("CONFIRMED", result.getAwardMethodStatus());
        assertEquals("추가확인필요", result.getReviewStatus());
        assertEquals("지역제한 조건 확인 필요, PQ심사 조건 확인 필요", result.getReviewReason());
        assertEquals("UNKNOWN", result.getExternalCheckStatus());
    }

    private JsonNode fixture(String name) throws Exception {
        return objectMapper.readTree(Files.readString(
                Path.of("src/test/resources/fixtures/koreaexpressway", name)
        ));
    }
}
