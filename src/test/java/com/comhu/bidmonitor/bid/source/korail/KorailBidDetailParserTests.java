package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KorailBidDetailParserTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KorailBidDetailParser parser = new KorailBidDetailParser(objectMapper);

    @Test
    void parsesDetailAttachmentsAndLongTextInSourceOrder() throws Exception {
        KorailBidDetailParser.ParsedDetail parsed = parser.parse(response(
                detailRows(detail()), documents(), longText(), objectMapper.createArrayNode()
        ));

        KorailBidDetailParser.Detail detail = parsed.detail();
        assertEquals("9258430-00", detail.noticeNo());
        assertEquals("00", detail.round());
        assertEquals("의왕ICD 통합운영플랫폼 구축 감리 용역", detail.title());
        assertEquals("제한경쟁", detail.contractMethod());
        assertEquals("협상에 의한 계약", detail.awardMethod());
        assertEquals("20261028", detail.bidEndDate());
        assertEquals("10:00:00", detail.bidEndTime());
        assertTrue(detail.bidEndDateTimeSet());

        assertEquals(4, parsed.attachments().size());
        assertEquals(new BigInteger("1"), parsed.attachments().getFirst().fileIndex());
        assertEquals("000001234567890001", parsed.attachments().getFirst().documentNumber());
        assertEquals(List.of(
                "1입찰공고문.hwp",
                "2용역설계서.hwp",
                "3제안요청서.hwp",
                "한국철도공사계약(용역)관련서류(260825).hwp"
        ), parsed.attachments().stream().map(KorailBidDetailParser.Attachment::fileName).toList());

        assertEquals(List.of("첫째 줄", "둘째 줄", "셋째 줄", "넷째 줄", "다섯째 줄"),
                parsed.noticeLines().stream().map(KorailBidDetailParser.LongTextLine::text).toList());
        assertEquals("첫째 줄\n둘째 줄\n셋째 줄\n넷째 줄\n다섯째 줄", parsed.noticeText());
    }

    @Test
    void createsAttachmentDtosWithoutAssumingDownloadUrls() throws Exception {
        KorailBidDetailParser.ParsedDetail parsed = parser.parse(response(
                detailRows(detail()), documents(), longText(), objectMapper.createArrayNode()
        ));

        List<BidAttachmentDto> attachments = parsed.bidAttachments();
        assertEquals(4, attachments.size());
        assertEquals("1입찰공고문.hwp", attachments.getFirst().getFileName());
        assertEquals("", attachments.getFirst().getFileUrl());
        assertEquals("BID", attachments.getFirst().getDocumentType());
        assertEquals("NOT_ANALYZED", attachments.getFirst().getAnalysisStatus());
    }

    @Test
    void acceptsEmptyResultTable() throws Exception {
        KorailBidDetailParser.ParsedDetail parsed = parser.parse(response(
                detailRows(detail()), documents(), longText(), objectMapper.createArrayNode()
        ));

        assertEquals("9258430-00", parsed.detail().noticeNo());
    }

    @Test
    void rejectsInvalidJsonAndDataError() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"data\":\"{" + "\"}"));
        assertThrows(IllegalStateException.class, () -> parser.parse("{\"data\":\"error\"}"));
    }

    @Test
    void rejectsMissingOrEmptyDetailTable() throws Exception {
        assertThrows(IllegalStateException.class, () -> parser.parse(response(
                null, documents(), longText(), objectMapper.createArrayNode()
        )));
        assertThrows(IllegalStateException.class, () -> parser.parse(response(
                objectMapper.createArrayNode(), documents(), longText(), objectMapper.createArrayNode()
        )));
    }

    @Test
    void treatsMissingOptionalTablesAsEmptyAndRejectsMalformedOptionalTables() throws Exception {
        KorailBidDetailParser.ParsedDetail parsed = parser.parse(response(
                detailRows(detail()), null, null, null
        ));

        assertEquals(List.of(), parsed.attachments());
        assertEquals(List.of(), parsed.noticeLines());
        assertEquals("", parsed.noticeText());

        ObjectNode notAnArray = objectMapper.createObjectNode();
        assertThrows(IllegalStateException.class, () -> parser.parse(response(
                detailRows(detail()), notAnArray, null, null
        )));
        assertThrows(IllegalStateException.class, () -> parser.parse(response(
                detailRows(detail()), null, notAnArray, null
        )));
        assertThrows(IllegalStateException.class, () -> parser.parse(response(
                detailRows(detail()), null, null, notAnArray
        )));
    }

    @Test
    void preservesUnsetDateValuesButDoesNotTreatThemAsValidDateTimes() throws Exception {
        ObjectNode detail = detail()
                .put("zzbid_erdat", "20261022")
                .put("zzbid_ertim", "10:00:00")
                .put("zzbidappl_dat", "00000000")
                .put("zzbidappl_tim", "00:00:00")
                .put("zztechrep_rdat", "20260230")
                .put("zztechrep_rtim", "10:00:00")
                .put("zzopen_dat", "20261022")
                .put("zzopen_tim", "00:00:00");

        KorailBidDetailParser.Detail parsed = parser.parse(response(
                detailRows(detail), documents(), longText(), objectMapper.createArrayNode()
        )).detail();

        assertEquals("00000000", parsed.applicationDate());
        assertEquals("00:00:00", parsed.applicationTime());
        assertFalse(parsed.applicationDateTimeSet());
        assertFalse(parsed.technicalSubmissionDateTimeSet());
        assertTrue(parsed.publishedDateTimeSet());
        assertTrue(parsed.openingDateTimeSet());
    }

    private ObjectNode detail() {
        return objectMapper.createObjectNode()
                .put("zzbidinv", "9258430-00")
                .put("zzstnum", "00")
                .put("description", "의왕ICD 통합운영플랫폼 구축 감리 용역")
                .put("zzbidinfo", "1")
                .put("zzbidinfo_t", "공고")
                .put("zzbidtypecode_t", "용역")
                .put("zzctrmethod_t", "제한경쟁")
                .put("zzwayofbidding_t", "협상에 의한 계약")
                .put("zzbidmethod_t", "전자입찰")
                .put("zzbid_erdat", "20260917")
                .put("zzbid_ertim", "13:17:18")
                .put("zzbid_sdat", "20261026")
                .put("zzbid_stim", "10:00:00")
                .put("zzbid_edat", "20261028")
                .put("zzbid_etim", "10:00:00")
                .put("zzbidappl_dat", "20261025")
                .put("zzbidappl_tim", "18:00:00")
                .put("zztechrep_rdat", "20261027")
                .put("zztechrep_rtim", "18:00:00")
                .put("zzopen_dat", "20261028")
                .put("zzopen_tim", "11:00:00")
                .put("zzcontprdt", "계약일로부터 12개월")
                .put("zzbidinv_new", "")
                .put("zzstnum_new", "");
    }

    private ArrayNode detailRows(ObjectNode detail) {
        return objectMapper.createArrayNode().add(detail);
    }

    private ArrayNode documents() {
        ArrayNode documents = objectMapper.createArrayNode();
        addDocument(documents, "1입찰공고문.hwp", 1, "000001234567890001", "BID", "00", "F1");
        addDocument(documents, "2용역설계서.hwp", 2, "000001234567890002", "DESIGN", "00", "F2");
        addDocument(documents, "3제안요청서.hwp", 3, "000001234567890003", "RFP", "00", "F3");
        addDocument(documents, "한국철도공사계약(용역)관련서류(260825).hwp", 4,
                "000001234567890004", "CONTRACT", "00", "F4");
        return documents;
    }

    private void addDocument(
            ArrayNode documents,
            String fileName,
            int fileIndex,
            String documentNumber,
            String documentType,
            String documentVersion,
            String fileId
    ) {
        documents.addObject()
                .put("filnm", fileName)
                .put("flidx", fileIndex)
                .put("doknr", documentNumber)
                .put("dokar", documentType)
                .put("dokvr", documentVersion)
                .put("filid", fileId);
    }

    private ArrayNode longText() {
        ArrayNode lines = objectMapper.createArrayNode();
        lines.addObject().put("tdformat", "*").put("tdline", "첫째 줄");
        lines.addObject().put("tdformat", "*").put("tdline", "둘째 줄");
        lines.addObject().put("tdformat", "*").put("tdline", "셋째 줄");
        lines.addObject().put("tdformat", "*").put("tdline", "넷째 줄");
        lines.addObject().put("tdformat", "*").put("tdline", "다섯째 줄");
        return lines;
    }

    private String response(
            JsonNode details,
            JsonNode documents,
            JsonNode longText,
            JsonNode result
    ) throws Exception {
        ArrayNode itab = objectMapper.createArrayNode();
        itab.addObject().put("name", "E_SUBRC").put("value", "S");
        itab.addObject().put("name", "E_MSG").put("value", "");
        if (result != null) {
            itab.addObject().put("name", "ET_RESULT").set("value", result);
        }
        if (details != null) {
            itab.addObject().put("name", "ET_ZSMMEEBID0004").set("value", details);
        }
        if (documents != null) {
            itab.addObject().put("name", "ET_DOCLIST").set("value", documents);
        }
        if (longText != null) {
            itab.addObject().put("name", "ET_LONG_TEXT").set("value", longText);
        }

        ObjectNode inner = objectMapper.createObjectNode();
        inner.set("itab", itab);
        return objectMapper.createObjectNode()
                .put("data", objectMapper.writeValueAsString(inner))
                .toString();
    }
}
