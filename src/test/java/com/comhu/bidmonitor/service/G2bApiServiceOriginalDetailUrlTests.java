package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class G2bApiServiceOriginalDetailUrlTests {

    @Test
    void preservesG2bDetailUrlInCommonBidDto() {
        String sourceDetailUrl =
                "https://www.g2b.go.kr/link/PNPE027_01/single/?bidPbancNo=20260901001";
        G2bApiService service = new G2bListFixtureService(sourceDetailUrl);

        LocalDate date = LocalDate.of(2026, 9, 1);
        List<BidDto> result = service.getBidDtoList(date, date, Set.of("6146"));

        assertEquals(1, result.size());
        assertEquals(sourceDetailUrl, result.getFirst().getBidNtceDtlUrl());
    }

    private static final class G2bListFixtureService extends G2bApiService {

        private final String sourceDetailUrl;

        private G2bListFixtureService(String sourceDetailUrl) {
            this.sourceDetailUrl = sourceDetailUrl;
        }

        @Override
        protected String requestBidListPage(
                LocalDate startDate,
                LocalDate endDate,
                String industryCode,
                int pageNo
        ) {
            return """
                    {
                      "response": {
                        "header": {"resultCode": "00", "resultMsg": "OK"},
                        "body": {
                          "totalCount": 1,
                          "items": [{
                            "bidNtceNo": "20260901001",
                            "bidNtceNm": "정보시스템 감리 용역",
                            "bidNtceDtlUrl": "%s"
                          }]
                        }
                      }
                    }
                    """.formatted(sourceDetailUrl);
        }

        @Override
        protected String requestBidTitleSearchPage(
                LocalDate startDate,
                LocalDate endDate,
                String keyword,
                int pageNo
        ) {
            return """
                    {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                    "body":{"totalCount":0,"items":[]}}}
                    """;
        }
    }
}
