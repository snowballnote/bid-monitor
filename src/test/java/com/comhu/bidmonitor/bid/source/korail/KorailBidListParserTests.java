package com.comhu.bidmonitor.bid.source.korail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KorailBidListParserTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KorailBidListParser parser = new KorailBidListParser(objectMapper);

    @Test
    void parsesDoubleEncodedListAndPreservesOriginalStrings() throws Exception {
        ObjectNode item = objectMapper.createObjectNode()
                .put("zzbidinv", "9258430-00")
                .put("zzstnum", "01")
                .put("description", "의왕ICD 통합운영플랫폼 구축 감리 용역")
                .put("zzbid_erdat", "20260917")
                .put("zzbid_ertim", "13:17:18")
                .put("zzbid_sdat", "20261026")
                .put("zzbid_stim", "10:00:00")
                .put("zzbid_edat", "20261028")
                .put("zzbid_etim", "10:00:00")
                .put("zzctrmethod_t", "제한경쟁")
                .put("zzwayofbidding_t", "협상에 의한 계약")
                .put("zzbidinfo", "1")
                .put("zzbidinv_new", "9259000-00");

        List<KorailBidListParser.Notice> notices = parser.parse(response("S", list(item)));
        assertEquals(1, notices.size());
        KorailBidListParser.Notice actual = notices.getFirst();
        assertEquals("9258430-00", actual.noticeNo());
        assertEquals("01", actual.round());
        assertEquals("의왕ICD 통합운영플랫폼 구축 감리 용역", actual.title());
        assertEquals("20260917", actual.publishedDate());
        assertEquals("13:17:18", actual.publishedTime());
        assertEquals("20261026", actual.bidStartDate());
        assertEquals("10:00:00", actual.bidStartTime());
        assertEquals("20261028", actual.bidEndDate());
        assertEquals("10:00:00", actual.bidEndTime());
        assertEquals("제한경쟁", actual.contractMethod());
        assertEquals("협상에 의한 계약", actual.awardMethod());
        assertEquals("1", actual.statusCode());
        assertEquals("9259000-00", actual.nextNoticeNo());
    }

    @Test
    void keepsMissingOptionalFieldsUnknownRatherThanGuessing() throws Exception {
        ObjectNode item = objectMapper.createObjectNode()
                .put("zzbidinv", "9258430-00")
                .put("zzstnum", "00");

        KorailBidListParser.Notice actual = parser.parse(response("S", list(item))).getFirst();
        assertEquals("", actual.title());
        assertEquals("", actual.statusCode());
        assertEquals("", actual.contractMethod());
        assertEquals("", actual.awardMethod());
        assertEquals("", actual.nextNoticeNo());
    }

    @Test
    void acceptsSuccessfulEmptyList() throws Exception {
        assertEquals(List.of(), parser.parse(response("S", objectMapper.createArrayNode())));
    }

    @Test
    void rejectsDataErrorEvenWhenHttpResponseWasSuccessful() {
        assertThrows(IllegalStateException.class, () -> parser.parse("{\"data\":\"error\"}"));
    }

    @Test
    void rejectsFailureAndMissingStatus() throws Exception {
        assertThrows(IllegalStateException.class, () -> parser.parse(response("E", list())));
        assertThrows(IllegalStateException.class, () -> parser.parse(response(null, list())));
    }

    @Test
    void rejectsMissingOrMalformedListTable() throws Exception {
        assertThrows(IllegalStateException.class, () -> parser.parse(response("S", null)));
        assertThrows(IllegalStateException.class, () -> parser.parse(response("S", objectMapper.createObjectNode())));
    }

    @Test
    void rejectsMissingNoticeIdentityWithoutSilentlyDroppingAnItem() throws Exception {
        assertThrows(IllegalStateException.class, () -> parser.parse(response("S", list(
                objectMapper.createObjectNode().put("zzstnum", "00")
        ))));
        assertThrows(IllegalStateException.class, () -> parser.parse(response("S", list(
                objectMapper.createObjectNode().put("zzbidinv", "9258430-00")
        ))));
    }

    @Test
    void rejectsInvalidOuterAndInnerJson() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"data\":\"{\"}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
    }

    private ArrayNode list(ObjectNode... items) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        return array;
    }

    private String response(String status, JsonNode tableValue) throws JsonProcessingException {
        ArrayNode itab = objectMapper.createArrayNode();
        if (status != null) {
            itab.addObject().put("name", "E_SUBRC").put("value", status);
        }
        // A successful KORAIL response can include a non-empty E_MSG.
        itab.addObject().put("name", "E_MSG").put("value", "업체지정이 되어 있습니다.");
        if (tableValue != null) {
            itab.addObject().put("name", "ET_ZSMMEEBID0083").set("value", tableValue);
        }
        ObjectNode inner = objectMapper.createObjectNode();
        inner.set("itab", itab);
        return objectMapper.createObjectNode()
                .put("data", objectMapper.writeValueAsString(inner))
                .toString();
    }
}
