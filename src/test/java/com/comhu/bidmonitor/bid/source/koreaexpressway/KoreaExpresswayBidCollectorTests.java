package com.comhu.bidmonitor.bid.source.koreaexpressway;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KoreaExpresswayBidCollectorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KoreaExpresswayBidCollector collector =
            new KoreaExpresswayBidCollector("https://ebid.ex.co.kr");

    @Test
    void convertsSourceSpecificQualificationReviewFieldsToCommonCandidate() throws Exception {
        JsonNode listItem = objectMapper.readTree("""
                {
                  "noti_no": "202608222",
                  "noti_nm": "AI기술을 활용한 통행료정보시스템 고도화 감리용역",
                  "noti_id": "18dfabd2-78d6-4516-9df0-719509258e03",
                  "noti_cont_id": "203a2dd1-1f77-4a20-9796-f2366708733f",
                  "bid_no": 1,
                  "bid_rev": 1
                }
                """);
        JsonNode detail = objectMapper.readTree("""
                {
                  "fileAttList": [],
                  "detailData": {
                    "noti_no": "202608222",
                    "noti_nm": "AI기술을 활용한 통행료정보시스템 고도화 감리용역",
                    "bid_start_dt": "202608271000",
                    "bid_end_dt": "202609041000",
                    "dsgng_amt": 134827834,
                    "bid_prtc_lcs": "공고문 참조",
                    "stl_terms": "STE",
                    "qual_insp_bas": "D",
                    "pq_yn": "N"
                  }
                }
                """);

        BidQualificationDto result = collector.toQualification(listItem, detail);

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
        assertEquals("적격심사제", result.getSucsfbidMthdNm());
        assertEquals("D", result.getSucsfbidMthdAppStd());
        assertEquals("N", result.getPqEvalYn());
    }
}
