package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KorailBidDtoMapperTests {

    private final KorailBidDtoMapper mapper = new KorailBidDtoMapper();

    @Test
    void mapsConfirmedDetailMetadataToCommonDto() {
        BidQualificationDto result = mapper.map(listNotice(), parsedDetail()).qualification();

        assertEquals("KORAIL", result.getSourceCode());
        assertEquals("9258430-00", result.getSourceNoticeId());
        assertEquals("9258430-00", result.getBidNtceNo());
        assertEquals("00", result.getRevision());
        assertEquals("의왕ICD 통합운영플랫폼 구축 감리 용역", result.getBidNtceNm());
        assertEquals("한국철도공사", result.getNtceInsttNm());
        assertEquals("2026-09-17 13:17", result.getBidNtceDt());
        assertEquals("2026-10-28 10:00", result.getBidClseDt());
        assertEquals("2026-10-28 11:00", result.getBidOpeningDt());
        assertEquals("제한경쟁", result.getContractMethod());
        assertEquals("전자입찰", result.getBidForm());
        assertEquals("협상에 의한 계약", result.getSucsfbidMthdNm());
        assertEquals("", result.getNoticeStatus());
        assertEquals("", result.getNoticeStatusCode());
        assertNull(result.getDetailUrl());
        assertNull(result.getBidNtceDtlUrl());
    }

    @Test
    void keepsPublishedDeadlineAndOtherSubmissionDatesSeparate() {
        BidQualificationDto result = mapper.map(listNotice(), parsedDetail()).qualification();

        assertEquals("2026-09-17 13:17", result.getBidNtceDt());
        assertEquals("2026-10-28 10:00", result.getBidClseDt());
        assertEquals("2026-10-28 11:00", result.getBidOpeningDt());
        // applicationDate and technicalSubmissionDate have no common deadline field and are not substituted.
    }

    @Test
    void acceptsMidnightWithValidDateAndRejectsUnsetDate() {
        KorailBidDetailParser.Detail midnight = detail(
                "20261022", "00:00:00", "00000000", "00:00:00"
        );
        BidQualificationDto result = mapper.map(
                listNotice(), new KorailBidDetailParser.ParsedDetail(midnight, List.of(), List.of())
        ).qualification();

        assertEquals("2026-10-22 00:00", result.getBidNtceDt());
        assertEquals("2026-10-16 10:00", result.getBidClseDt());

        KorailBidListParser.Notice unsetListDate = new KorailBidListParser.Notice(
                "9258430-00", "00", "title",
                "00000000", "00:00:00", "", "", "00000000", "00:00:00",
                "", "", "UNKNOWN", ""
        );
        BidQualificationDto unsetResult = mapper.map(unsetListDate).qualification();
        assertEquals("", unsetResult.getBidNtceDt());
        assertEquals("", unsetResult.getBidClseDt());
    }

    @Test
    void preservesAttachmentOrderAndKorailOnlyIdentifiers() {
        KorailBidDtoMapper.MappingResult result = mapper.map(listNotice(), parsedDetail());

        assertEquals(List.of("file-1.hwp", "file-2.hwp", "3제안요청서.hwp", "file-4.hwp"),
                result.qualification().getAttachments().stream().map(BidAttachmentDto::getFileName).toList());
        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4)),
                result.sourceAttachments().stream().map(KorailBidDtoMapper.AttachmentMapping::fileIndex).toList());
        assertEquals(List.of("000000000000000001", "000000000000000002",
                        "000000000000000003", "000000000000000004"),
                result.sourceAttachments().stream()
                        .map(KorailBidDtoMapper.AttachmentMapping::documentNumber).toList());
        result.qualification().getAttachments().forEach(attachment -> {
            assertEquals("", attachment.getFileUrl());
            assertEquals("NOT_ANALYZED", attachment.getAnalysisStatus());
        });
    }

    @Test
    void mapsListOnlyWithoutInventingDetailFacts() {
        KorailBidDtoMapper.MappingResult mapped = mapper.map(listNotice());
        BidQualificationDto result = mapped.qualification();

        assertEquals("2026-09-17 13:17", result.getBidNtceDt());
        assertEquals("2026-10-16 10:00", result.getBidClseDt());
        assertEquals("", result.getBidOpeningDt());
        assertEquals(List.of(), result.getAttachments());
        assertEquals(List.of(), mapped.sourceAttachments());
        assertEquals("UNKNOWN", result.getExternalCheckStatus());
        assertNull(result.getExternalSiteCheckRequired());
    }

    @Test
    void rejectsListAndDetailIdentityMismatch() {
        KorailBidListParser.Notice wrongNotice = notice("DIFFERENT-00", "00");
        KorailBidListParser.Notice wrongRound = notice("9258430-00", "01");

        assertThrows(IllegalArgumentException.class, () -> mapper.map(wrongNotice, parsedDetail()));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(wrongRound, parsedDetail()));
    }

    @Test
    void leavesQualificationAndProposalFactsUnknown() {
        BidQualificationDto result = mapper.map(listNotice()).qualification();

        assertEquals("", result.getNoticeStatus());
        assertEquals("", result.getNoticeStatusCode());
        assertEquals("UNKNOWN", result.getAwardMethodCategory());
        assertEquals("UNKNOWN", result.getAwardMethodStatus());
        assertEquals("NOT_AVAILABLE", result.getAwardMethodSource());
        assertEquals("UNKNOWN", result.getExternalCheckStatus());
        assertNull(result.getExternalSiteCheckRequired());
        assertEquals("", result.getLicenseLimit());
        assertEquals(List.of(), result.getLicenseGroups());
        assertNull(result.getReviewStatus());
        assertNull(result.getReviewReason());
    }

    private KorailBidListParser.Notice listNotice() {
        return notice("9258430-00", "00");
    }

    private KorailBidListParser.Notice notice(String noticeNo, String round) {
        return new KorailBidListParser.Notice(
                noticeNo,
                round,
                "의왕ICD 통합운영플랫폼 구축 감리 용역",
                "20260917",
                "13:17:18",
                "20261010",
                "10:00:00",
                "20261016",
                "10:00:00",
                "제한경쟁",
                "협상에 의한 계약",
                "UNRECOGNIZED_SOURCE_STATUS",
                ""
        );
    }

    private KorailBidDetailParser.ParsedDetail parsedDetail() {
        List<KorailBidDetailParser.Attachment> attachments = List.of(
                attachment("file-1.hwp", 1, "000000000000000001"),
                attachment("file-2.hwp", 2, "000000000000000002"),
                attachment("3제안요청서.hwp", 3, "000000000000000003"),
                attachment("file-4.hwp", 4, "000000000000000004")
        );
        return new KorailBidDetailParser.ParsedDetail(
                detail("20260917", "13:17:18", "20261028", "10:00:00"),
                attachments,
                List.of()
        );
    }

    private KorailBidDetailParser.Detail detail(
            String publishedDate,
            String publishedTime,
            String bidEndDate,
            String bidEndTime
    ) {
        return new KorailBidDetailParser.Detail(
                "9258430-00",
                "00",
                "의왕ICD 통합운영플랫폼 구축 감리 용역",
                "1",
                "일반공고",
                "용역",
                "제한경쟁",
                "협상에 의한 계약",
                "전자입찰",
                publishedDate,
                publishedTime,
                "20261026",
                "10:00:00",
                bidEndDate,
                bidEndTime,
                "20261025",
                "18:00:00",
                "20261027",
                "18:00:00",
                "20261028",
                "11:00:00",
                "계약일로부터 12개월",
                "",
                ""
        );
    }

    private KorailBidDetailParser.Attachment attachment(String name, long index, String documentNumber) {
        return new KorailBidDetailParser.Attachment(
                name, BigInteger.valueOf(index), documentNumber, "BID", "00", "F" + index
        );
    }
}
