package com.comhu.bidmonitor.bid.source.kwater;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KwaterBidCollectorTests {

    private static final String API_URL = "https://apis.data.go.kr/B500001/ebid/tndr3";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void executionRequiresBothEnabledFlagAndServiceKey() {
        assertFalse(collector("", true, uri -> emptyResponse()).executionEnabled());
        assertFalse(collector("test-key", false, uri -> emptyResponse()).executionEnabled());
        assertTrue(collector("test-key", true, uri -> emptyResponse()).executionEnabled());
    }

    @Test
    void rejectsNonOfficialBaseUrlBeforeSendingServiceKey() {
        assertThrows(IllegalArgumentException.class, () -> new KwaterBidCollector(
                "https://example.test/B500001/ebid/tndr3", "test-key", true, uri -> emptyResponse()
        ));
    }

    @Test
    void singleDateUsesOneSearchMonthForEveryOfficialOperation() {
        List<URI> requests = new ArrayList<>();
        KwaterBidCollector collector = collector("test-key", true, uri -> {
            requests.add(uri);
            return emptyResponse();
        });

        collector.collect(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14));

        assertEquals(8, requests.size());
        assertEquals(List.of(
                        "servcList", "cntrwkList", "dmscptList", "gdsList",
                        "rstList", "rstList", "rstList", "rstList"
                ),
                requests.stream().map(KwaterBidCollectorTests::lastPathSegment).toList());
        assertTrue(requests.stream().allMatch(uri -> "202610".equals(query(uri).get("searchDt"))));
        assertTrue(requests.stream().allMatch(uri -> "json".equals(query(uri).get("_type"))));
        assertEquals(List.of("1", "2", "3", "4"), requests.stream()
                .filter(uri -> lastPathSegment(uri).equals("rstList"))
                .map(uri -> query(uri).get("cntrctDivCd"))
                .toList());
    }

    @Test
    void rangeAcrossMonthBoundaryQueriesEachMonthOnce() {
        List<URI> requests = new ArrayList<>();
        KwaterBidCollector collector = collector("test-key", true, uri -> {
            requests.add(uri);
            return emptyResponse();
        });

        collector.collect(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2));

        assertEquals(16, requests.size());
        assertEquals(8, requests.stream().filter(uri -> "202609".equals(query(uri).get("searchDt"))).count());
        assertEquals(8, requests.stream().filter(uri -> "202610".equals(query(uri).get("searchDt"))).count());
    }

    @Test
    void filtersMonthlyResultsBackToRequestedDateRange() {
        KwaterBidCollector collector = collector("test-key", true, uri -> {
            if (lastPathSegment(uri).equals("servcList")) {
                return response(3, 100,
                        item("KW-OUT-1", "20261013", "범위 전"),
                        item("KW-IN", "20261014", "범위 내"),
                        item("KW-OUT-2", "20261015", "범위 후"));
            }
            return emptyResponse();
        });

        List<BidQualificationDto> result = collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        );

        assertEquals(List.of("KW-IN"), result.stream().map(BidQualificationDto::getSourceNoticeId).toList());
    }

    @Test
    void followsTotalCountPaginationAndStopsAfterLastPage() {
        AtomicInteger serviceCalls = new AtomicInteger();
        List<URI> requests = new ArrayList<>();
        KwaterBidCollector collector = new KwaterBidCollector(
                API_URL, "test-key", true, uri -> {
                    requests.add(uri);
                    if (!lastPathSegment(uri).equals("servcList")) {
                        return emptyResponse(1);
                    }
                    int page = Integer.parseInt(query(uri).get("pageNo"));
                    serviceCalls.incrementAndGet();
                    return page == 1
                            ? response(2, 1, item("KW-1", "20261014", "첫 공고"))
                            : response(2, 1, item("KW-2", "20261014", "둘째 공고"));
                }, JSON, 1
        );

        List<BidQualificationDto> result = collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        );

        assertEquals(2, serviceCalls.get());
        assertEquals(List.of("KW-1", "KW-2"), result.stream()
                .map(BidQualificationDto::getSourceNoticeId).toList());
        assertEquals(9, requests.size());
    }

    @Test
    void rejectsRepeatedPagesBeforePaginationCanLoopForever() {
        KwaterBidCollector collector = new KwaterBidCollector(
                API_URL, "test-key", true,
                uri -> lastPathSegment(uri).equals("servcList")
                        ? response(3, 1, item("KW-1", "20261014", "반복"))
                        : emptyResponse(1),
                JSON, 1
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        ));

        assertTrue(failure.getMessage().contains("same page"));
    }

    @Test
    void deduplicatesTheSameNoticeAcrossOperationsWithoutInventingRevision() {
        KwaterBidCollector collector = collector("test-key", true, uri -> response(
                1, 100, item("B5202604000", "20261014", "중복 공고")
        ));

        List<BidQualificationDto> result = collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        );

        assertEquals(1, result.size());
        assertEquals("KWATER", result.getFirst().getSourceCode());
        assertEquals("B5202604000", result.getFirst().getSourceNoticeId());
        assertEquals("B5202604000", result.getFirst().getBidNtceNo());
        assertNull(result.getFirst().getRevision());
    }

    @Test
    void mapsOnlyOfficialListFieldsAndLeavesQualificationAndAttachmentsEmpty() {
        Map<String, Object> item = item("B5202604001", "20261014", "K-water 시스템 운영 용역");
        item.put("tndrPblancEnddt", "20261021");
        item.put("ctrmthdCdNm", "제한경쟁");
        item.put("tndrStat", "진행중");
        item.put("tndrPlnprc", 123456789);
        item.put("cntrctDivNm", "용역");
        KwaterBidCollector collector = collector("test-key", true, uri ->
                lastPathSegment(uri).equals("servcList")
                        ? response(1, 100, item)
                        : emptyResponse()
        );

        BidQualificationDto value = collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        ).getFirst();

        assertEquals("K-water 시스템 운영 용역", value.getBidNtceNm());
        assertEquals("20261014", value.getBidNtceDt());
        assertEquals("20261021", value.getBidClseDt());
        assertEquals("제한경쟁", value.getContractMethod());
        assertEquals("진행중", value.getNoticeStatus());
        assertEquals("123456789", value.getAsignBdgtAmt());
        assertEquals("용역", value.getBidForm());
        assertTrue(value.getDetailUrl().contains("tndrPbanno=B5202604001"));
        assertFalse(value.getDetailUrl().contains("test-key"));
        assertTrue(value.getLicenseGroups().isEmpty());
        assertTrue(value.getAttachments().isEmpty());
    }

    @Test
    void doesNotApplyACollectorSpecificTitleFilter() {
        KwaterBidCollector collector = collector("test-key", true, uri ->
                lastPathSegment(uri).equals("cntrwkList")
                        ? response(1, 100, item("B3202604002", "20261014", "일반 건설공사"))
                        : emptyResponse()
        );

        List<BidQualificationDto> result = collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        );

        assertEquals(List.of("B3202604002"), result.stream()
                .map(BidQualificationDto::getSourceNoticeId).toList());
    }

    @Test
    void skipsMalformedItemsButFailsForAnApiError() {
        KwaterBidCollector malformed = collector("test-key", true, uri ->
                lastPathSegment(uri).equals("servcList")
                        ? response(2, 100,
                                item("", "20261014", "번호 없음"),
                                item("KW-BAD-DATE", "not-a-date", "날짜 오류"))
                        : emptyResponse()
        );
        assertTrue(malformed.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        ).isEmpty());

        KwaterBidCollector apiError = collector("test-key", true, uri -> errorResponse("22"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> apiError.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        ));
        assertTrue(failure.getMessage().contains("22"));
    }

    @Test
    void transportFailureCannotExposeServiceKeyThroughCollectorError() {
        String secret = "secret-service-key";
        KwaterBidCollector collector = collector(secret, true, uri -> {
            throw new IllegalStateException("failed URL: " + uri);
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> collector.collect(
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14)
        ));

        assertFalse(failure.getMessage().contains(secret));
        assertNull(failure.getCause());
    }

    private static KwaterBidCollector collector(
            String serviceKey,
            boolean enabled,
            KwaterBidCollector.Transport transport
    ) {
        return new KwaterBidCollector(API_URL, serviceKey, enabled, transport);
    }

    private static String emptyResponse() {
        return emptyResponse(100);
    }

    private static String emptyResponse(int pageSize) {
        return response(0, pageSize);
    }

    @SafeVarargs
    private static String response(int totalCount, int pageSize, Map<String, Object>... items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", Map.of("resultCode", "00", "resultMsg", "NORMAL SERVICE"));
        response.put("body", Map.of(
                "items", Map.of("item", List.of(items)),
                "numOfRows", pageSize,
                "pageNo", 1,
                "totalCount", totalCount
        ));
        return json(Map.of("response", response));
    }

    private static String errorResponse(String resultCode) {
        return json(Map.of("response", Map.of(
                "header", Map.of("resultCode", resultCode, "resultMsg", "LIMIT"),
                "body", Map.of("items", Map.of(), "numOfRows", 100, "pageNo", 1, "totalCount", 0)
        )));
    }

    private static Map<String, Object> item(String noticeId, String noticeDate, String title) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tndrPbanno", noticeId);
        item.put("tndrPblancNm", title);
        item.put("tndrPblancDe", noticeDate);
        return item;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String lastPathSegment(URI uri) {
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            );
        }
        return values;
    }
}
