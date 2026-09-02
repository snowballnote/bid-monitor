package com.comhu.bidmonitor.bid.source.koreaexpressway;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 공개사이트 연결은 명시적으로 활성화할 때만 실행하는 통합 검증이다. */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_BID_SOURCE_TEST", matches = "true")
class KoreaExpresswayBidCollectorLiveTests {

    @Test
    void collectsKnownQualificationReviewNoticeFromPublicSource() {
        KoreaExpresswayBidCollector collector = new KoreaExpresswayBidCollector("https://ebid.ex.co.kr");

        List<BidQualificationDto> result = collector.collect(
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27)
        );

        BidQualificationDto notice = result.stream()
                .filter(item -> "202608222-00".equals(item.getBidNtceNo()))
                .findFirst()
                .orElseThrow();
        assertEquals("적격심사제", notice.getSucsfbidMthdNm());
        assertEquals("D", notice.getSucsfbidMthdAppStd());
        assertTrue(notice.getBidNtceNm().contains("감리용역"));
    }
}
