package com.comhu.bidmonitor.bid.source.lh;

import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import com.comhu.bidmonitor.service.BidQualificationEvaluationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhBidCollectorTests {

    private static final String API_URL =
            "https://apis.data.go.kr/B552555/OpenBidInfoList/getOpenBidInfo";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Test
    void executionIsDisabledWithoutServiceKey() {
        assertFalse(new LhBidCollector(API_URL, "", true, uri -> emptyResponse()).executionEnabled());
    }

    @Test
    void executionIsDisabledWhenFlagIsFalse() {
        assertFalse(new LhBidCollector(API_URL, "test-key", false, uri -> emptyResponse()).executionEnabled());
    }

    @Test
    void executionIsEnabledOnlyWhenFlagAndKeyArePresent() {
        assertTrue(new LhBidCollector(API_URL, "test-key", true, uri -> emptyResponse()).executionEnabled());
    }

    @Test
    void rejectsNonOfficialApiBaseUrlBeforeSendingTheServiceKey() {
        assertThrows(IllegalArgumentException.class, () -> new LhBidCollector(
                "https://example.test/OpenBidInfoList/getOpenBidInfo",
                "test-key", true, uri -> emptyResponse()
        ));
    }

    @Test
    void sendsBasicIsoDatesAndPaginationParameters() {
        List<URI> requests = new ArrayList<>();
        LhBidCollector collector = new LhBidCollector(API_URL, "test-key", true, uri -> {
            requests.add(uri);
            return emptyResponse();
        });

        collector.collect(START, END);

        assertEquals(1, requests.size());
        Map<String, String> query = query(requests.getFirst());
        assertEquals("20260901", query.get("tndrbidRegDtStart"));
        assertEquals("20260930", query.get("tndrbidRegDtEnd"));
        assertEquals("1", query.get("pageNo"));
        assertEquals("100", query.get("numOfRows"));
    }

    @Test
    void followsTotalCountAndDeduplicatesByBidNumberAndDegree() {
        AtomicInteger listCalls = new AtomicInteger();
        LhBidCollector collector = new LhBidCollector(API_URL, "test-key", true, uri -> {
            int page = Integer.parseInt(query(uri).get("pageNo"));
            listCalls.incrementAndGet();
            return page == 1
                    ? response(3, item("LH-1", "00", "첫 공고", auditLicense()),
                            item("LH-2", "00", "둘째 공고", softwareLicense()))
                    : response(3, item("LH-1", "00", "중복 공고", auditLicense()),
                            item("LH-1", "01", "정정 공고", auditLicense()));
        }, 2);

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(2, listCalls.get());
        assertEquals(List.of("LH-1:00", "LH-2:00", "LH-1:01"), result.stream()
                .map(value -> value.getSourceNoticeId() + ":" + value.getRevision()).toList());
    }

    @Test
    void rejectsRepeatedPagesBeforePaginationCanLoopForever() {
        String page = response(3, item("LH-1", "00", "첫 공고", auditLicense()),
                item("LH-2", "00", "둘째 공고", softwareLicense()));
        LhBidCollector collector = new LhBidCollector(
                API_URL, "test-key", true, uri -> page, 2
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> collector.collect(START, END)
        );

        assertTrue(failure.getMessage().contains("same page"));
    }

    @Test
    void mapsConfirmedApiFieldsWithoutInventingNames() {
        String fields = """
                <tndrbidRegDt>20260915</tndrbidRegDt>
                <tndrdocAcptEndDtm>202609301600</tndrdocAcptEndDtm>
                <openDtm>202610011000</openDtm>
                <tndrCtrctMedCd>제한경쟁</tndrCtrctMedCd>
                <sunjungNm>적격심사</sunjungNm>
                <presmtPrc>100000000</presmtPrc>
                <fdmtlAmt>110000000</fdmtlAmt>
                <zoneRstrct1>서울특별시</zoneRstrct1>
                <zoneRstrct2>경기도</zoneRstrct2>
                <bidProgrsStatus>입찰진행중</bidProgrsStatus>
                """ + softwareLicense();
        LhBidCollector collector = collector(uri -> response(
                1, item("2600001", "02", "LH 정보화 용역", fields)
        ));

        BidQualificationDto value = collector.collect(START, END).getFirst();

        assertEquals("LH", value.getSourceCode());
        assertEquals("2600001", value.getSourceNoticeId());
        assertEquals("02", value.getRevision());
        assertEquals("LH 정보화 용역", value.getBidNtceNm());
        assertEquals("20260915", value.getBidNtceDt());
        assertEquals("202609301600", value.getBidClseDt());
        assertEquals("제한경쟁", value.getContractMethod());
        assertEquals("적격심사", value.getSucsfbidMthdNm());
        assertEquals("100000000", value.getAsignBdgtAmt());
        assertEquals("서울특별시, 경기도", value.getParticipationRegion());
        assertTrue(value.getDetailUrl().contains("bidNum=2600001"));
        assertTrue(value.getDetailUrl().contains("bidDegree=02"));
    }

    @Test
    void normalizesOnlyTheTwoExactOfficialLicenseNames() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("req1Reqlic1Nm", "정보시스템 감리법인");
        values.put("req1Reqlic2Nm", "소프트웨어사업자(컴퓨터관련서비스사업)");
        values.put("req2Reqlic1Nm", "정보통신공사업");

        List<LicenseRequirementGroup> groups = LhBidCollector.parseApiLicenseGroups(values);

        assertEquals(List.of("6146", "1468"), groups.getFirst().getRequirements().stream()
                .map(LicenseRequirement::getLicenseCode).toList());
        assertEquals("", groups.get(1).getRequirements().getFirst().getLicenseCode());
        assertEquals("정보통신공사업", groups.get(1).getRequirements().getFirst().getLicenseName());
    }

    @Test
    void supplementsMissingApiQualificationFromOfficialDetailPage() {
        List<URI> requests = new ArrayList<>();
        LhBidCollector collector = collector(uri -> {
            requests.add(uri);
            if ("ebid.lh.or.kr".equals(uri.getHost())) {
                return detail("소프트웨어사업자(컴퓨터관련서비스사업)", "제한경쟁", "적격심사", "접수");
            }
            return response(1, item("2600002", "00", "정보화 운영 용역", ""));
        });

        BidQualificationDto value = collector.collect(START, END).getFirst();

        assertEquals(2, requests.size());
        assertEquals("1468", value.getLicenseGroups().getFirst().getRequirements().getFirst().getLicenseCode());
        assertEquals("제한경쟁", value.getContractMethod());
        assertEquals("적격심사", value.getSucsfbidMthdNm());
        assertEquals("Y", value.getPqEvalYn());
        assertEquals("서울특별시", value.getParticipationRegion());
    }

    @Test
    void missingQualificationInApiAndDetailRemainsAdditionalReview() {
        LhBidCollector collector = collector(uri -> "ebid.lh.or.kr".equals(uri.getHost())
                ? detail(null, "제한경쟁", "적격심사", "미접수")
                : response(1, item("2600003", "00", "자격 미기재 용역", "")));

        BidQualificationDto value = collector.collect(START, END).getFirst();
        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(value, Set.of("6146", "1468"));

        assertTrue(value.getLicenseGroups().isEmpty());
        assertEquals("추가확인필요", value.getReviewStatus());
        assertTrue(value.getReviewReason().contains("면허조건 정보 확인 필요"));
    }

    @Test
    void doesNotMapUnrelatedDetailLicenseToAllowedCodes() {
        LhBidCollector collector = collector(uri -> "ebid.lh.or.kr".equals(uri.getHost())
                ? detail("정보통신공사업", "제한경쟁", "적격심사", "미접수")
                : response(1, item("2600005", "00", "정보통신 운영 용역", "")));

        BidQualificationDto value = collector.collect(START, END).getFirst();
        LicenseRequirement requirement = value.getLicenseGroups().getFirst()
                .getRequirements().getFirst();

        assertEquals("", requirement.getLicenseCode());
        assertEquals("정보통신공사업", requirement.getLicenseName());
    }

    @Test
    void defersTitleRelevanceUntilQualificationIsAvailable() {
        LhBidCollector collector = collector(uri -> {
            if ("ebid.lh.or.kr".equals(uri.getHost())) {
                return detail(null, "제한경쟁", "적격심사", "미접수");
            }
            return response(6,
                    item("LH-IT", "00", "대기자 통합시스템 구축 1차 감리 용역", ""),
                    item("LH-UNKNOWN", "00", "감리 용역", ""),
                    item("LH-WASTE", "00", "건설폐기물 처리 용역", ""),
                    item("LH-SAFETY", "00", "지하안전평가 용역", ""),
                    item("LH-TELECOM", "00", "정보통신공사 감리 용역", ""),
                    item("LH-CONSTRUCTION", "00", "일반 건설 감리 용역", ""));
        });

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(List.of(
                "LH-IT", "LH-UNKNOWN", "LH-WASTE", "LH-SAFETY", "LH-TELECOM", "LH-CONSTRUCTION"
        ), result.stream()
                .map(BidQualificationDto::getBidNtceNo).toList());
    }

    @Test
    void detailFailureKeepsApiCandidateForAdditionalReview() {
        LhBidCollector collector = collector(uri -> {
            if ("ebid.lh.or.kr".equals(uri.getHost())) {
                throw new IllegalStateException("fixture detail failure");
            }
            return response(1, item("2600004", "00", "상세 실패 용역", ""));
        });

        List<BidQualificationDto> result = collector.collect(START, END);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().getLicenseGroups().isEmpty());
        new BidQualificationEvaluationService(new BidAwardMethodClassifier())
                .evaluate(result.getFirst(), Set.of("6146", "1468"));
        assertEquals("추가확인필요", result.getFirst().getReviewStatus());
    }

    @Test
    void transportFollowsRedirectAndReturnsFinalResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/detail");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/detail", exchange -> {
            byte[] body = "<html><body>detail</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpClient redirectingClient = LhBidCollector.JdkTransport.httpClient(
                    HttpClient.Redirect.NORMAL, null
            );
            LhBidCollector.JdkTransport transport = new LhBidCollector.JdkTransport(
                    redirectingClient, redirectingClient
            );

            String result = transport.get(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"
            ));

            assertEquals("<html><body>detail</body></html>", result);
        } finally {
            server.stop(0);
        }
    }

    private LhBidCollector collector(LhBidCollector.Transport transport) {
        return new LhBidCollector(API_URL, "test-key", true, transport);
    }

    private static String emptyResponse() {
        return response(0);
    }

    private static String response(int totalCount, String... items) {
        return "<response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE</resultMsg></header>"
                + "<body><items>" + String.join("", items) + "</items><totalCount>" + totalCount
                + "</totalCount></body></response>";
    }

    private static String item(String bidNum, String degree, String title, String fields) {
        return "<item><bidNum>" + bidNum + "</bidNum><bidDegree>" + degree + "</bidDegree>"
                + "<cstrtnJobGbNm>용역</cstrtnJobGbNm><bidnmKor>" + title + "</bidnmKor>"
                + fields + "</item>";
    }

    private static String auditLicense() {
        return "<req1Reqlic1Nm>정보시스템 감리법인</req1Reqlic1Nm>"
                + "<tndrCtrctMedCd>제한경쟁</tndrCtrctMedCd><sunjungNm>적격심사</sunjungNm>";
    }

    private static String softwareLicense() {
        return "<req1Reqlic1Nm>소프트웨어사업자(컴퓨터관련서비스사업)</req1Reqlic1Nm>"
                + "<tndrCtrctMedCd>제한경쟁</tndrCtrctMedCd><sunjungNm>적격심사</sunjungNm>";
    }

    private static String detail(String license, String contract, String award, String pq) {
        String licenseTable = license == null ? "" : """
                <h2>요구면허</h2><table><tr><th>번호</th><th>요구면허1</th></tr>
                <tr><td>1</td><td>%s</td></tr></table>
                """.formatted(license);
        return """
                <html><body><table>
                <tr><th>계약방법</th><td>%s</td></tr>
                <tr><th>낙찰자선정방법</th><td>%s</td></tr>
                <tr><th>PQ심사실시여부</th><td>%s</td></tr>
                <tr><th>참가지역1</th><td>서울특별시</td></tr>
                </table>%s</body></html>
                """.formatted(contract, award, pq, licenseTable);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int delimiter = pair.indexOf('=');
            result.put(delimiter < 0 ? pair : pair.substring(0, delimiter),
                    delimiter < 0 ? "" : pair.substring(delimiter + 1));
        }
        return result;
    }
}
