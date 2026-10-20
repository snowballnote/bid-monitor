package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maps parsed KORAIL metadata to the common DTO without evaluating bid suitability. */
public final class KorailBidDtoMapper {

    static final String SOURCE_CODE = "KORAIL";
    private static final String ORDERING_ORGANIZATION = "한국철도공사";
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public MappingResult map(KorailBidListParser.Notice notice) {
        return map(notice, null);
    }

    public MappingResult map(
            KorailBidListParser.Notice notice,
            KorailBidDetailParser.ParsedDetail parsedDetail
    ) {
        Objects.requireNonNull(notice, "notice");
        KorailBidDetailParser.Detail detail = parsedDetail == null ? null : parsedDetail.detail();
        if (detail != null
                && (!notice.noticeNo().equals(detail.noticeNo()) || !notice.round().equals(detail.round()))) {
            throw new IllegalArgumentException("KORAIL list and detail identities do not match.");
        }

        List<AttachmentMapping> attachmentMappings = mapAttachments(parsedDetail);
        BidQualificationDto qualification = new BidQualificationDto();
        qualification.setSourceCode(SOURCE_CODE);
        qualification.setSourceNoticeId(notice.noticeNo());
        qualification.setRevision(notice.round());
        qualification.setBidNtceNo(notice.noticeNo());
        qualification.setBidNtceNm(firstNonBlank(detail == null ? "" : detail.title(), notice.title()));
        qualification.setNtceInsttNm(ORDERING_ORGANIZATION);
        qualification.setBidNtceDt(firstValidDateTime(
                detail == null ? "" : detail.publishedDate(),
                detail == null ? "" : detail.publishedTime(),
                notice.publishedDate(),
                notice.publishedTime()
        ));
        qualification.setBidClseDt(firstValidDateTime(
                detail == null ? "" : detail.bidEndDate(),
                detail == null ? "" : detail.bidEndTime(),
                notice.bidEndDate(),
                notice.bidEndTime()
        ));
        qualification.setBidOpeningDt(detail == null
                ? ""
                : formatDateTime(detail.openingDate(), detail.openingTime()));
        qualification.setContractMethod(firstNonBlank(
                detail == null ? "" : detail.contractMethod(), notice.contractMethod()
        ));
        qualification.setBidForm(detail == null ? "" : detail.bidMethod());
        qualification.setSucsfbidMthdNm(firstNonBlank(
                detail == null ? "" : detail.awardMethod(), notice.awardMethod()
        ));
        qualification.setSucsfbidMthdCd("");
        // KORAIL's zzbidinfo/zzbidinfo_t describe a source notice classification
        // (for example, "1"/"일반공고"), not a confirmed bid progress state.
        qualification.setNoticeStatus("");
        qualification.setNoticeStatusCode("");
        qualification.setAttachments(attachmentMappings.stream().map(AttachmentMapping::attachment).toList());

        // Qualification and document-analysis facts are not present in the parsed metadata.
        qualification.setLicenseLimit("");
        qualification.setLicenseGroups(List.of());
        qualification.setParticipationRegion("");
        qualification.setArsltCmptYn("");
        qualification.setPqEvalYn("");
        qualification.setTpEvalYn("");
        qualification.setCmmnSpldmdAgrmntRcptdocMethd("");
        qualification.setExternalCheckStatus("UNKNOWN");
        qualification.setExternalSiteCheckRequired(null);
        qualification.setExternalSiteUrls(new ArrayList<>());
        qualification.setExternalCheckReason("");
        return new MappingResult(qualification, attachmentMappings);
    }

    private List<AttachmentMapping> mapAttachments(KorailBidDetailParser.ParsedDetail parsedDetail) {
        if (parsedDetail == null) {
            return List.of();
        }
        return parsedDetail.attachments().stream()
                .map(source -> new AttachmentMapping(
                        source.toBidAttachment(),
                        source.fileIndex(),
                        source.documentNumber(),
                        source.documentVersion(),
                        source.fileId()
                ))
                .toList();
    }

    private String firstValidDateTime(
            String preferredDate,
            String preferredTime,
            String fallbackDate,
            String fallbackTime
    ) {
        String preferred = formatDateTime(preferredDate, preferredTime);
        return preferred.isBlank() ? formatDateTime(fallbackDate, fallbackTime) : preferred;
    }

    private String formatDateTime(String date, String time) {
        if (date == null || time == null || date.isBlank() || time.isBlank() || "00000000".equals(date)) {
            return "";
        }
        try {
            LocalDate parsedDate = LocalDate.parse(date, SOURCE_DATE);
            LocalTime parsedTime = LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME);
            return LocalDateTime.of(parsedDate, parsedTime).format(DISPLAY_DATE_TIME);
        } catch (DateTimeParseException exception) {
            return "";
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : Objects.requireNonNullElse(second, "");
    }

    public record MappingResult(
            BidQualificationDto qualification,
            List<AttachmentMapping> sourceAttachments
    ) {
    }

    /** Keeps KORAIL-only attachment identifiers alongside the common attachment DTO. */
    public record AttachmentMapping(
            BidAttachmentDto attachment,
            BigInteger fileIndex,
            String documentNumber,
            String documentVersion,
            String fileId
    ) {
    }
}
