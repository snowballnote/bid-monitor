
package com.comhu.bidmonitor.bid.source.kogas;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KogasNoticeMatcherTests {

    @Test
    void matchesSameNoticeCode() {
        var api = apiNotice("2026101427");
        var html = htmlNotice("2026101427", "BID-A", "1");

        var result = KogasNoticeMatcher.findPotentialMatches(
                api, List.of(html)
        );

        assertEquals(1, result.size());
        assertEquals("2026101427:BID-A",
                result.getFirst().getSourceNoticeId());
    }

    @Test
    void preservesDifferentBidCodesAndRounds() {
        var api = apiNotice("2026101427");

        var first = htmlNotice("2026101427", "BID-A", "1");
        var second = htmlNotice("2026101427", "BID-B", "2");

        var result = KogasNoticeMatcher.findPotentialMatches(
                api, List.of(first, second)
        );

        assertEquals(2, result.size());
        assertEquals("1", result.get(0).getRevision());
        assertEquals("2", result.get(1).getRevision());
    }

    @Test
    void excludesDifferentNoticeCodes() {
        var api = apiNotice("2026101427");
        var html = htmlNotice("2026101428", "BID-A", "1");

        assertTrue(KogasNoticeMatcher.findPotentialMatches(
                api, List.of(html)
        ).isEmpty());
    }

    private KogasOpenApiParser.KogasApiNotice apiNotice(String code) {
        return new KogasOpenApiParser.KogasApiNotice(
                code, "회계자문용역", "전자입찰", "용역",
                "일반경쟁", "2026-10-14",
                "", "", ""
        );
    }

    private BidQualificationDto htmlNotice(
            String code, String bidCode, String round
    ) {
        var notice = new BidQualificationDto();
        notice.setSourceCode("KOGAS");
        notice.setBidNtceNo(code);
        notice.setSourceNoticeId(code + ":" + bidCode);
        notice.setRevision(round);
        return notice;
    }
}
