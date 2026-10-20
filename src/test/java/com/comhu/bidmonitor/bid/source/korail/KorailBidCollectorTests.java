package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KorailBidCollectorTests {

    private static final LocalDate START_DATE = LocalDate.of(2026, 9, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 10, 19);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reportsKorailSourceAndRemainsDisabled() {
        KorailBidCollector collector = collector(request -> {
            throw new AssertionError("transport must not be called");
        });

        assertEquals("KORAIL", collector.sourceCode());
        assertFalse(collector.executionEnabled());
        assertFalse(collector.registrationBindingSupported());
    }

    @Test
    void collectsListAndDetailIntoCommonDtoWithoutEvaluatingPolicy() {
        FakeTransport transport = new FakeTransport(
                response(200, listResponse(notice("9258430-00", "00"))),
                response(200, detailResponse("9258430-00", "00"))
        );

        List<BidQualificationDto> result = collector(transport).collect(START_DATE, END_DATE);

        assertEquals(1, result.size());
        BidQualificationDto bid = result.getFirst();
        assertEquals("KORAIL", bid.getSourceCode());
        assertEquals("9258430-00", bid.getSourceNoticeId());
        assertEquals("9258430-00", bid.getBidNtceNo());
        assertEquals("00", bid.getRevision());
        assertEquals("의왕ICD 통합운영플랫폼 구축 감리 용역", bid.getBidNtceNm());
        assertEquals("", bid.getNoticeStatusCode());
        assertEquals("", bid.getNoticeStatus());
        assertEquals("협상에 의한 계약", bid.getSucsfbidMthdNm());
        assertNull(bid.getReviewStatus());
        assertNull(bid.getReviewReason());
        assertEquals(2, transport.callCount());
    }

    @Test
    void preservesAttachmentOrderAndUnanalyzedState() {
        FakeTransport transport = new FakeTransport(
                response(200, listResponse(notice("9258430-00", "00"))),
                response(200, detailResponse("9258430-00", "00"))
        );

        List<BidAttachmentDto> attachments = collector(transport)
                .collect(START_DATE, END_DATE).getFirst().getAttachments();

        assertEquals(List.of("file-1.hwp", "file-2.hwp", "file-3.hwp", "file-4.hwp"),
                attachments.stream().map(BidAttachmentDto::getFileName).toList());
        attachments.forEach(attachment -> {
            assertEquals("", attachment.getFileUrl());
            assertEquals("NOT_ANALYZED", attachment.getAnalysisStatus());
        });
    }

    @Test
    void fallsBackToListMetadataWhenDetailFails() {
        FakeTransport transport = new FakeTransport(
                response(200, listResponse(notice("9258430-00", "00"))),
                response(503, "sensitive response body")
        );

        BidQualificationDto bid = collector(transport).collect(START_DATE, END_DATE).getFirst();

        assertEquals("9258430-00", bid.getSourceNoticeId());
        assertEquals("00", bid.getRevision());
        assertEquals(List.of(), bid.getAttachments());
        assertEquals("UNKNOWN", bid.getExternalCheckStatus());
        assertNull(bid.getExternalSiteCheckRequired());
        assertNull(bid.getReviewStatus());
        assertNull(bid.getReviewReason());
    }

    @Test
    void propagatesListFailure() {
        FakeTransport transport = new FakeTransport(response(503, "sensitive response body"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> collector(transport).collect(START_DATE, END_DATE)
        );

        assertEquals("KORAIL list request failed with HTTP 503.", exception.getMessage());
        assertEquals(1, transport.callCount());
    }

    @Test
    void returnsEmptyListWithoutDetailRequests() {
        FakeTransport transport = new FakeTransport(response(200, listResponse()));

        assertEquals(List.of(), collector(transport).collect(START_DATE, END_DATE));
        assertEquals(1, transport.callCount());
    }

    @Test
    void rejectsInvalidDateRangeBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        KorailBidCollector collector = collector(transport);

        assertThrows(IllegalArgumentException.class, () -> collector.collect(END_DATE, START_DATE));
        assertThrows(IllegalArgumentException.class, () -> collector.collect(null, END_DATE));
        assertThrows(IllegalArgumentException.class, () -> collector.collect(START_DATE, null));
        assertEquals(0, transport.callCount());
    }

    @Test
    void deduplicatesTheSameNoticeAndRoundBeforeDetailLookup() {
        ObjectNode duplicate = notice("9258430-00", "00");
        FakeTransport transport = new FakeTransport(
                response(200, listResponse(duplicate, duplicate.deepCopy())),
                response(200, detailResponse("9258430-00", "00"))
        );

        List<BidQualificationDto> result = collector(transport).collect(START_DATE, END_DATE);

        assertEquals(1, result.size());
        assertEquals("9258430-00", result.getFirst().getSourceNoticeId());
        assertEquals("00", result.getFirst().getRevision());
        assertEquals(2, transport.callCount());
    }

    private KorailBidCollector collector(KorailBidHttpClient.Transport transport) {
        KorailBidHttpClient client = new KorailBidHttpClient(
                "https://example.test",
                objectMapper,
                new KorailBidListParser(objectMapper),
                new KorailBidDetailParser(objectMapper),
                transport
        );
        return new KorailBidCollector(client, new KorailBidDtoMapper());
    }

    private KorailBidHttpClient.Response response(int statusCode, String body) {
        return new KorailBidHttpClient.Response(statusCode, body);
    }

    private ObjectNode notice(String noticeNo, String round) {
        return objectMapper.createObjectNode()
                .put("zzbidinv", noticeNo)
                .put("zzstnum", round)
                .put("description", "의왕ICD 통합운영플랫폼 구축 감리 용역")
                .put("zzbid_erdat", "20260917")
                .put("zzbid_ertim", "13:17:18")
                .put("zzbid_edat", "20261028")
                .put("zzbid_etim", "10:00:00")
                .put("zzctrmethod_t", "제한경쟁")
                .put("zzwayofbidding_t", "협상에 의한 계약")
                .put("zzbidinfo", "1");
    }

    private String listResponse(ObjectNode... notices) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (ObjectNode notice : notices) {
            rows.add(notice);
        }
        ArrayNode itab = objectMapper.createArrayNode();
        itab.addObject().put("name", "E_SUBRC").put("value", "S");
        itab.addObject().put("name", "ET_ZSMMEEBID0083").set("value", rows);
        return outerResponse(itab);
    }

    private String detailResponse(String noticeNo, String round) {
        ArrayNode detailRows = objectMapper.createArrayNode();
        detailRows.addObject()
                .put("zzbidinv", noticeNo)
                .put("zzstnum", round)
                .put("description", "의왕ICD 통합운영플랫폼 구축 감리 용역")
                .put("zzbidinfo", "1")
                .put("zzbidinfo_t", "일반공고")
                .put("zzctrmethod_t", "제한경쟁")
                .put("zzwayofbidding_t", "협상에 의한 계약")
                .put("zzbidmethod_t", "전자입찰")
                .put("zzbid_erdat", "20260917")
                .put("zzbid_ertim", "13:17:18")
                .put("zzbid_edat", "20261028")
                .put("zzbid_etim", "10:00:00")
                .put("zzopen_dat", "20261028")
                .put("zzopen_tim", "11:00:00");

        ArrayNode documents = objectMapper.createArrayNode();
        for (int index = 1; index <= 4; index++) {
            documents.addObject()
                    .put("filnm", "file-" + index + ".hwp")
                    .put("flidx", index)
                    .put("doknr", "00000000000000000" + index)
                    .put("dokar", "BID")
                    .put("dokvr", "00")
                    .put("filid", "F" + index);
        }

        ArrayNode lines = objectMapper.createArrayNode();
        for (int index = 1; index <= 5; index++) {
            lines.addObject().put("tdformat", "*").put("tdline", "line-" + index);
        }

        ArrayNode itab = objectMapper.createArrayNode();
        itab.addObject().put("name", "E_SUBRC").put("value", "S");
        itab.addObject().put("name", "ET_RESULT").set("value", objectMapper.createArrayNode());
        itab.addObject().put("name", "ET_ZSMMEEBID0004").set("value", detailRows);
        itab.addObject().put("name", "ET_DOCLIST").set("value", documents);
        itab.addObject().put("name", "ET_LONG_TEXT").set("value", lines);
        return outerResponse(itab);
    }

    private String outerResponse(ArrayNode itab) {
        try {
            ObjectNode inner = objectMapper.createObjectNode().set("itab", itab);
            return objectMapper.createObjectNode()
                    .put("data", objectMapper.writeValueAsString(inner))
                    .toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeTransport implements KorailBidHttpClient.Transport {
        private final Queue<KorailBidHttpClient.Response> responses = new ArrayDeque<>();
        private final List<java.net.http.HttpRequest> requests = new ArrayList<>();

        private FakeTransport(KorailBidHttpClient.Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public KorailBidHttpClient.Response send(java.net.http.HttpRequest request) {
            requests.add(request);
            KorailBidHttpClient.Response response = responses.poll();
            if (response == null) {
                throw new AssertionError("unexpected HTTP request");
            }
            return response;
        }

        private int callCount() {
            return requests.size();
        }
    }
}
