package com.comhu.bidmonitor.bid.source.d2b;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class D2bBidCollectorTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 21);

    @Test
    void mapsFourDomesticAndFacilityOperationsAndEnrichesOnlyRelevantNotices() {
        RecordingTransport transport = new RecordingTransport(uri -> {
            String path = path(uri);
            if (path.endsWith("Detail")) {
                return detailFor(path);
            }
            return switch (path) {
                case "getDmstcCmpetBidPblancList" -> list(item("""
                        <pblancSeCode>02</pblancSeCode><pblancSe>정정공고</pblancSe>
                        <demandYear>2026</demandYear><pblancDate>20260902</pblancDate>
                        <pblancNo>D-100</pblancNo><pblancOdr>2</pblancOdr><dcsNo>DC-1</dcsNo>
                        <bidNm>국방 정보시스템 감리 용역</bidNm><orntCode>ARMY</orntCode><ornt>육군본부</ornt>
                        <biddocPresentnClosDt>202609201000</biddocPresentnClosDt><opengDt>202609201100</opengDt>
                        """));
                case "getDmstcOthbcVltrnNtatPlanList" -> list(item("""
                        <demandYear>2026</demandYear><pblancNo>D-200</pblancNo><pblancOdr>1</pblancOdr>
                        <dcsNo>DC-2</dcsNo><iemNo>1</iemNo><ntatPlanDate>20260903</ntatPlanDate>
                        <othbcNtatNm>정보화 감리 용역</othbcNtatNm><orntCode>NAVY</orntCode><ornt>해군본부</ornt>
                        <prqudoPresentnClosDt>202609211000</prqudoPresentnClosDt><progrsSttus>진행중</progrsSttus>
                        """));
                case "getFcltyCmpetBidPblancList" -> list(item("""
                        <pblancYear>2026</pblancYear><pblancSeCode>01</pblancSeCode><pblancSe>정상공고</pblancSe>
                        <pblancDate>20260904</pblancDate><pblancNo>F-300</pblancNo><pblancOdr>1</pblancOdr>
                        <cntrwkNo>FC-3</cntrwkNo><cntrwkNm>클라우드 구축 감리 용역</cntrwkNm>
                        <orntCode>AIR</orntCode><ornt>공군본부</ornt>
                        """));
                case "getFcltyOthbcVltrnNtatPlanList" -> list(item("""
                        <pblancNo>F-400</pblancNo><pblancOdr>1</pblancOdr><cntrwkNo>FC-4</cntrwkNo>
                        <cntrwkNm>개인정보 영향평가</cntrwkNm><orntCode>DAPA</orntCode><ornt>방위사업청</ornt>
                        <ntatPlanDate>20260905</ntatPlanDate><prqudoPresentnClosDt>202609221000</prqudoPresentnClosDt>
                        """));
                default -> emptyList();
            };
        });

        List<BidQualificationDto> result = collector(transport).collect(START, END);

        assertEquals(4, result.size());
        BidQualificationDto domestic = find(result, "D-100");
        assertEquals("D2B", domestic.getSourceCode());
        assertEquals("2026:ARMY:D-100", domestic.getSourceNoticeId());
        assertEquals("2", domestic.getRevision());
        assertEquals("육군본부", domestic.getNtceInsttNm());
        assertEquals("제한경쟁", domestic.getContractMethod());
        assertEquals("전자입찰", domestic.getBidForm());
        assertEquals("적격심사제", domestic.getSucsfbidMthdNm());
        assertEquals("정정공고", domestic.getNoticeStatus());
        assertEquals("02", domestic.getNoticeStatusCode());
        assertNull(domestic.getDetailUrl());
        assertNull(domestic.getBidNtceDtlUrl());

        BidQualificationDto facility = find(result, "F-300");
        assertEquals("시설 제한경쟁", facility.getContractMethod());
        assertEquals("전자입찰", facility.getBidForm());
        assertEquals("202609251100", facility.getBidOpeningDt());

        BidQualificationDto negotiation = find(result, "D-200");
        assertEquals("공개수의", negotiation.getContractMethod());
        assertEquals("전자협상", negotiation.getBidForm());
        assertEquals("진행중", negotiation.getNoticeStatus());

        assertEquals("개인정보 영향평가", find(result, "F-400").getBidNtceNm());
        assertEquals(4, transport.detailCalls.get());
    }

    @Test
    void excludesConstructionFireAndElectricalSupervisionWithoutItContext() {
        RecordingTransport transport = new RecordingTransport(uri -> {
            if (path(uri).endsWith("Detail")) {
                return detail("<cntrctMth>제한경쟁</cntrctMth>");
            }
            return list(
                    item(common("C-1", "건설사업관리 감리 용역", "1")),
                    item(common("C-2", "소방 전기 감리 용역", "1")),
                    item(common("I-1", "전산시스템 구축 감리 용역", "1"))
            );
        });

        List<BidQualificationDto> result = collector(transport).collect(START, END);

        assertEquals(1, result.size());
        assertEquals("I-1", result.getFirst().getBidNtceNo());
        assertEquals(1, transport.detailCalls.get());
    }

    @Test
    void keepsDifferentRevisionsAndDeduplicatesSameRevisionAcrossOperationsBeforeDetail() {
        AtomicInteger detailCalls = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport(uri -> {
            String path = path(uri);
            if (path.endsWith("Detail")) {
                detailCalls.incrementAndGet();
                return detail("<pblancNo>SHARED</pblancNo>");
            }
            if (path.equals("getDmstcCmpetBidPblancList")) {
                return list(item(common("SHARED", "정보시스템 감리", "1")),
                        item(common("SHARED", "정보시스템 감리 정정", "2")));
            }
            if (path.equals("getFcltyCmpetBidPblancList")) {
                return list(item(commonFacility("SHARED", "정보시스템 감리", "1")));
            }
            return emptyList();
        });

        List<BidQualificationDto> result = collector(transport).collect(START, END);

        assertEquals(List.of("1", "2"), result.stream().map(BidQualificationDto::getRevision).toList());
        assertEquals(2, detailCalls.get());
    }

    @Test
    void isolatesOneListOperationFailureAndReturnsOtherOperationResults() {
        RecordingTransport transport = new RecordingTransport(uri -> {
            String path = path(uri);
            if (path.equals("getDmstcCmpetBidPblancList")) {
                throw new IllegalStateException("fixture failure");
            }
            if (path.equals("getDmstcOthbcVltrnNtatPlanList")) {
                return list(item("""
                        <demandYear>2026</demandYear><pblancNo>N-1</pblancNo><pblancOdr>1</pblancOdr>
                        <dcsNo>D-1</dcsNo><iemNo>1</iemNo><ntatPlanDate>20260901</ntatPlanDate>
                        <othbcNtatNm>개인정보영향평가</othbcNtatNm><orntCode>DAPA</orntCode>
                        """));
            }
            return path.endsWith("Detail") ? detail("<cntrctMth>공개수의</cntrctMth>") : emptyList();
        });

        List<BidQualificationDto> result = collector(transport).collect(START, END);

        assertEquals(1, result.size());
        assertEquals("N-1", result.getFirst().getBidNtceNo());
    }

    @Test
    void malformedXmlFailsSafelyWhenAllOperationsFail() {
        RecordingTransport transport = new RecordingTransport(uri -> "<response><broken>");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> collector(transport).collect(START, END)
        );

        assertTrue(exception.getMessage().contains("D2B 목록 오퍼레이션"));
        assertTrue(!exception.getMessage().contains("test-key"));
    }

    @Test
    void emptyResultsReturnEmptyList() {
        RecordingTransport transport = new RecordingTransport(uri -> emptyList());

        assertTrue(collector(transport).collect(START, END).isEmpty());
        assertEquals(20, transport.uris.size());
    }

    @Test
    void followsPaginationAndPassesOfficialTitleAndDateParameters() {
        RecordingTransport transport = new RecordingTransport(uri -> {
            Map<String, String> query = query(uri);
            if (!path(uri).equals("getDmstcCmpetBidPblancList")
                    || !"정보시스템 감리".equals(query.get("bidNm"))) {
                return path(uri).endsWith("Detail") ? detail("<cntrctMth>제한경쟁</cntrctMth>") : emptyList();
            }
            if ("2".equals(query.get("pageNo"))) {
                return listWithTotal(101, item(common("PAGE-2", "정보시스템 감리", "1")));
            }
            return listWithTotal(101);
        });

        List<BidQualificationDto> result = collector(transport).collect(START, END);

        assertEquals(1, result.size());
        assertTrue(transport.uris.stream().map(D2bBidCollectorTests::query)
                .anyMatch(values -> "20260901".equals(values.get("anmtDateBegin"))
                        && "20260921".equals(values.get("anmtDateEnd"))
                        && "2".equals(values.get("pageNo"))));
    }

    @Test
    void requiresDedicatedServiceKeyAndValidRange() {
        D2bBidCollector missingKey = new D2bBidCollector("https://example.test", "", uri -> emptyList());

        assertThrows(IllegalStateException.class, () -> missingKey.collect(START, END));
        assertThrows(IllegalArgumentException.class, () -> collector(uri -> emptyList()).collect(END, START));
    }

    private static D2bBidCollector collector(D2bBidCollector.Transport transport) {
        return new D2bBidCollector("https://example.test/BidPblancInfoService", "test-key", transport);
    }

    private static BidQualificationDto find(List<BidQualificationDto> values, String noticeNumber) {
        return values.stream().filter(value -> noticeNumber.equals(value.getBidNtceNo())).findFirst().orElseThrow();
    }

    private static String detailFor(String path) {
        return switch (path) {
            case "getDmstcCmpetBidPblancDetail" -> detail("""
                    <pblancNo>D-100</pblancNo><cntrctMth>제한경쟁</cntrctMth><bidStle>전자입찰</bidStle>
                    <sucbidrDecsnMth>적격심사제</sucbidrDecsnMth><lcnsLmttList>정보시스템감리법인</lcnsLmttList>
                    """);
            case "getDmstcOthbcVltrnNtatPlanDetail" -> detail("""
                    <pblancNo>D-200</pblancNo><cntrctMth>공개수의</cntrctMth><ntatStle>전자협상</ntatStle>
                    <sucbidrDecsnMth>최저가격제</sucbidrDecsnMth>
                    """);
            case "getFcltyCmpetBidPblancDetail" -> detail("""
                    <pblancNo>F-300</pblancNo><cntrctMth>시설 제한경쟁</cntrctMth><bidStle>전자입찰</bidStle>
                    <opengDt>202609251100</opengDt>
                    """);
            case "getFcltyOthbcVltrnNtatPlanDetail" -> detail("""
                    <pblancNo>F-400</pblancNo><cntrctMth>공개수의</cntrctMth><ntatStle>전자협상</ntatStle>
                    """);
            default -> throw new IllegalArgumentException(path);
        };
    }

    private static String common(String number, String title, String revision) {
        return "<demandYear>2026</demandYear><pblancNo>" + number + "</pblancNo>"
                + "<pblancOdr>" + revision + "</pblancOdr><dcsNo>DC</dcsNo>"
                + "<bidNm>" + title + "</bidNm><orntCode>ORG</orntCode><ornt>기관</ornt>";
    }

    private static String commonFacility(String number, String title, String revision) {
        return "<pblancYear>2026</pblancYear><pblancSeCode>01</pblancSeCode>"
                + "<pblancNo>" + number + "</pblancNo><pblancOdr>" + revision + "</pblancOdr>"
                + "<cntrwkNo>WORK</cntrwkNo><cntrwkNm>" + title + "</cntrwkNm>"
                + "<orntCode>ORG</orntCode><ornt>기관</ornt>";
    }

    private static String item(String fields) {
        return "<item>" + fields + "</item>";
    }

    private static String list(String... items) {
        return listWithTotal(items.length, items);
    }

    private static String emptyList() {
        return listWithTotal(0);
    }

    private static String listWithTotal(int total, String... items) {
        return "<response><header><resultCode>00</resultCode></header><body><totalCount>" + total
                + "</totalCount><pageNo>1</pageNo><numOfRows>100</numOfRows><items>"
                + String.join("", items) + "</items></body></response>";
    }

    private static String detail(String fields) {
        return "<response><header><resultCode>00</resultCode></header><body><item>"
                + fields + "</item></body></response>";
    }

    private static String path(URI uri) {
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class RecordingTransport implements D2bBidCollector.Transport {
        private final Function<URI, String> response;
        private final List<URI> uris = new ArrayList<>();
        private final AtomicInteger detailCalls = new AtomicInteger();

        private RecordingTransport(Function<URI, String> response) {
            this.response = response;
        }

        @Override
        public String get(URI uri) {
            uris.add(uri);
            if (path(uri).endsWith("Detail")) {
                detailCalls.incrementAndGet();
            }
            return response.apply(uri);
        }
    }
}
