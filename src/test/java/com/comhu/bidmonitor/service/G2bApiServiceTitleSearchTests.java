package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceTitleSearchTests {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);

    @Test
    void mergesIndustryAndRelevantTitleSearchResultsWithoutDuplicates() {
        TitleSearchFixtureService service = new TitleSearchFixtureService();
        service.industry = List.of(bid("G2B-6146", "기존 업종코드 공고"));
        service.titles.put("정보시스템 감리", List.of(
                bid("G2B-6146", "기존 업종코드 공고"),
                bid("G2B-INFO", "차세대 정보시스템 감리 용역")
        ));
        service.titles.put("정보화 감리", List.of(bid("G2B-INFO", "차세대 정보시스템 감리 용역")));
        service.titles.put("개인정보 영향평가", List.of(
                bid("G2B-PIA", "개인정보 영향평가 수행 용역")
        ));
        service.titles.put("개인정보영향평가", List.of(
                bid("G2B-PIA", "개인정보영향평가 수행 용역")
        ));
        service.titles.put("감리", List.of(
                bid("G2B-IT", "전산시스템 구축 감리 용역"),
                bid("G2B-CONSTRUCTION", "공동주택 건설공사 감리용역"),
                bid("G2B-FIRE", "소방시설 감리용역")
        ));

        List<BidDto> result = service.getBidDtoList(DATE, DATE);

        assertEquals(List.of("G2B-6146", "G2B-INFO", "G2B-PIA", "G2B-IT"),
                result.stream().map(BidDto::getBidNtceNo).toList());
    }

    @Test
    void keepsDistinctCorrectionNoticeIdsAndDeduplicatesRepeatedSearchHits() {
        TitleSearchFixtureService service = new TitleSearchFixtureService();
        service.titles.put("정보시스템 감리", List.of(
                bid("NOTICE-00", "정보시스템 감리 용역"),
                bid("NOTICE-01", "정보시스템 감리 용역 정정공고")
        ));
        service.titles.put("감리", List.of(
                bid("NOTICE-00", "정보시스템 감리 용역"),
                bid("NOTICE-01", "정보시스템 감리 용역 정정공고")
        ));

        List<BidDto> result = service.getBidDtoList(DATE, DATE);

        assertEquals(List.of("NOTICE-00", "NOTICE-01"),
                result.stream().map(BidDto::getBidNtceNo).toList());
    }

    @Test
    void titleSearchFailureDoesNotAffectIndustryCodeResults() {
        TitleSearchFixtureService service = new TitleSearchFixtureService();
        service.industry = List.of(bid("G2B-6146", "기존 업종코드 공고"));
        service.failedKeywords.addAll(List.of(
                "정보시스템 감리", "정보화 감리", "개인정보 영향평가", "개인정보영향평가", "감리"
        ));

        List<BidDto> result = service.getBidDtoList(DATE, DATE);

        assertEquals(List.of("G2B-6146"), result.stream().map(BidDto::getBidNtceNo).toList());
    }

    @Test
    void titleOnlyCandidateUsesExistingDetailAnalysisPipeline() {
        DetailFixtureService service = new DetailFixtureService();
        service.titles.put("개인정보 영향평가", List.of(
                bid("G2B-PIA", "개인정보 영향평가 수행 용역")
        ));

        List<BidQualificationDto> result = service.getTargetBidQualificationList(DATE, DATE, Set.of("6146"));

        assertEquals(List.of("G2B-PIA"), service.detailRequests);
        assertEquals(1, result.size());
        assertEquals("G2B-PIA", result.getFirst().getBidNtceNo());
        assertEquals("G2B", result.getFirst().getSourceCode());
    }

    @Test
    void titleSearchUsesDocumentedParameterAndExistingPagingRules() {
        CapturingRequestService requestService = new CapturingRequestService();
        requestService.requestTitle(DATE, DATE, "개인정보 영향평가", 2);

        assertTrue(requestService.requestUrl.contains("/getBidPblancListInfoServcPPSSrch"));
        assertTrue(requestService.requestUrl.contains("inqryDiv=1"));
        assertTrue(requestService.requestUrl.contains("pageNo=2"));
        assertTrue(requestService.requestUrl.contains("numOfRows=100"));
        assertTrue(requestService.requestUrl.contains("bidNtceNm=%EA%B0%9C%EC%9D%B8%EC%A0%95%EB%B3%B4+%EC%98%81%ED%96%A5%ED%8F%89%EA%B0%80"));

        TitleSearchFixtureService pagingService = new TitleSearchFixtureService();
        pagingService.titles.put("정보시스템 감리", List.of(bid("PAGE-1", "정보시스템 감리 용역")));
        pagingService.titleTotals.put("정보시스템 감리", 101);
        pagingService.titleSecondPages.put("정보시스템 감리", List.of(
                bid("PAGE-2", "정보시스템 감리 용역 정정공고")
        ));

        List<BidDto> result = pagingService.getBidDtoList(DATE, DATE);

        assertEquals(List.of("PAGE-1", "PAGE-2"),
                result.stream().map(BidDto::getBidNtceNo).toList());
        assertTrue(pagingService.titleRequests.contains("정보시스템 감리:2"));
    }

    private static BidDto bid(String noticeId, String title) {
        BidDto bid = new BidDto();
        bid.setBidNtceNo(noticeId);
        bid.setBidNtceNm(title);
        return bid;
    }

    private static String response(List<BidDto> bids, int totalCount) {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode response = root.putObject("response");
        response.putObject("header").put("resultCode", "00").put("resultMsg", "OK");
        ObjectNode body = response.putObject("body").put("totalCount", totalCount);
        ArrayNode items = body.putArray("items");
        for (BidDto bid : bids) {
            items.addObject()
                    .put("bidNtceNo", bid.getBidNtceNo())
                    .put("bidNtceNm", bid.getBidNtceNm());
        }
        return root.toString();
    }

    private static class TitleSearchFixtureService extends G2bApiService {
        private List<BidDto> industry = List.of();
        protected final Map<String, List<BidDto>> titles = new HashMap<>();
        private final Map<String, List<BidDto>> titleSecondPages = new HashMap<>();
        private final Map<String, Integer> titleTotals = new HashMap<>();
        private final Set<String> failedKeywords = new HashSet<>();
        private final List<String> titleRequests = new ArrayList<>();

        @Override
        protected String requestBidListPage(LocalDate startDate, LocalDate endDate, int pageNo) {
            return response(pageNo == 1 ? industry : List.of(), industry.size());
        }

        @Override
        protected String requestBidTitleSearchPage(
                LocalDate startDate,
                LocalDate endDate,
                String keyword,
                int pageNo
        ) {
            titleRequests.add(keyword + ":" + pageNo);
            if (failedKeywords.contains(keyword)) {
                throw new IllegalStateException("upstream failure");
            }
            List<BidDto> page = pageNo == 1
                    ? titles.getOrDefault(keyword, List.of())
                    : titleSecondPages.getOrDefault(keyword, List.of());
            return response(page, titleTotals.getOrDefault(keyword, page.size()));
        }
    }

    private static final class DetailFixtureService extends TitleSearchFixtureService {
        private final List<String> detailRequests = new ArrayList<>();

        @Override
        public BidQualificationDto getBidQualification(String bidNtceNo, Set<String> allowedLicenseCodes) {
            detailRequests.add(bidNtceNo);
            BidQualificationDto qualification = new BidQualificationDto();
            qualification.setBidNtceNo(bidNtceNo);
            return qualification;
        }
    }

    private static final class CapturingRequestService extends G2bApiService {
        private String requestUrl;

        private void requestTitle(LocalDate startDate, LocalDate endDate, String keyword, int pageNo) {
            requestBidTitleSearchPage(startDate, endDate, keyword, pageNo);
        }

        @Override
        protected String executeBidListRequest(String requestUrl) {
            this.requestUrl = requestUrl;
            return "";
        }
    }
}
