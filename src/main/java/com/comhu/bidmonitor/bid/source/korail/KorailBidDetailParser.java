package com.comhu.bidmonitor.bid.source.korail;

import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Parses a KORAIL service-bid detail response without making HTTP requests. */
public final class KorailBidDetailParser {

    private static final String DETAIL_TABLE = "ET_ZSMMEEBID0004";
    private static final String DOCUMENT_TABLE = "ET_DOCLIST";
    private static final String LONG_TEXT_TABLE = "ET_LONG_TEXT";
    private static final String RESULT_TABLE = "ET_RESULT";

    private final ObjectMapper objectMapper;

    public KorailBidDetailParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ParsedDetail parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalArgumentException("KORAIL detail response is blank.");
        }

        JsonNode outer = parseJson(responseBody);
        if (!outer.isObject()) {
            throw new IllegalStateException("KORAIL detail response root must be an object.");
        }

        JsonNode data = outer.path("data");
        if (!data.isTextual()) {
            throw new IllegalStateException("KORAIL detail response does not contain textual data.");
        }
        String innerJson = data.textValue();
        if (innerJson.isBlank() || "error".equalsIgnoreCase(innerJson.strip())) {
            throw new IllegalStateException("KORAIL detail request returned an error result.");
        }

        JsonNode inner = parseJson(innerJson);
        JsonNode itab = inner.path("itab");
        if (!itab.isArray()) {
            throw new IllegalStateException("KORAIL detail response does not contain an itab array.");
        }

        JsonNode status = findValue(itab, "E_SUBRC");
        if (status == null || !status.isTextual() || !"S".equals(status.textValue())) {
            throw new IllegalStateException("KORAIL detail request was not successful.");
        }

        JsonNode detailRows = requiredArray(itab, DETAIL_TABLE);
        optionalArray(itab, RESULT_TABLE); // Present values must be arrays; their contents are not used here.
        JsonNode documentRows = optionalArray(itab, DOCUMENT_TABLE);
        JsonNode longTextRows = optionalArray(itab, LONG_TEXT_TABLE);
        if (detailRows.isEmpty()) {
            throw new IllegalStateException("KORAIL detail response contains no detail row.");
        }
        JsonNode detailRow = detailRows.get(0);
        if (!detailRow.isObject()) {
            throw new IllegalStateException("KORAIL detail row must be an object.");
        }

        return new ParsedDetail(
                toDetail(detailRow),
                parseAttachments(documentRows),
                parseLongText(longTextRows)
        );
    }

    private Detail toDetail(JsonNode row) {
        return new Detail(
                requiredText(row, "zzbidinv"),
                requiredText(row, "zzstnum"),
                text(row, "description"),
                text(row, "zzbidinfo"),
                text(row, "zzbidinfo_t"),
                text(row, "zzbidtypecode_t"),
                text(row, "zzctrmethod_t"),
                text(row, "zzwayofbidding_t"),
                text(row, "zzbidmethod_t"),
                text(row, "zzbid_erdat"),
                text(row, "zzbid_ertim"),
                text(row, "zzbid_sdat"),
                text(row, "zzbid_stim"),
                text(row, "zzbid_edat"),
                text(row, "zzbid_etim"),
                text(row, "zzbidappl_dat"),
                text(row, "zzbidappl_tim"),
                text(row, "zztechrep_rdat"),
                text(row, "zztechrep_rtim"),
                text(row, "zzopen_dat"),
                text(row, "zzopen_tim"),
                text(row, "zzcontprdt"),
                text(row, "zzbidinv_new"),
                text(row, "zzstnum_new")
        );
    }

    private List<Attachment> parseAttachments(JsonNode rows) {
        List<Attachment> attachments = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw new IllegalStateException("KORAIL document row must be an object.");
            }
            JsonNode fileIndex = row.path("flidx");
            if (!fileIndex.isIntegralNumber()) {
                throw new IllegalStateException("KORAIL document flidx must be an integer.");
            }
            attachments.add(new Attachment(
                    requiredText(row, "filnm"),
                    fileIndex.bigIntegerValue(),
                    requiredText(row, "doknr"),
                    text(row, "dokar"),
                    text(row, "dokvr"),
                    text(row, "filid")
            ));
        }
        return List.copyOf(attachments);
    }

    private List<LongTextLine> parseLongText(JsonNode rows) {
        List<LongTextLine> lines = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw new IllegalStateException("KORAIL long-text row must be an object.");
            }
            lines.add(new LongTextLine(text(row, "tdformat"), text(row, "tdline")));
        }
        return List.copyOf(lines);
    }

    private JsonNode requiredArray(JsonNode itab, String name) {
        JsonNode value = findValue(itab, name);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("KORAIL detail response is missing array table " + name + ".");
        }
        return value;
    }

    private JsonNode optionalArray(JsonNode itab, String name) {
        JsonNode value = findValue(itab, name);
        if (value == null) {
            return objectMapper.createArrayNode();
        }
        if (!value.isArray()) {
            throw new IllegalStateException("KORAIL detail table " + name + " must be an array.");
        }
        return value;
    }

    private static JsonNode findValue(JsonNode itab, String name) {
        for (JsonNode entry : itab) {
            if (name.equals(text(entry, "name"))) {
                return entry.get("value");
            }
        }
        return null;
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("KORAIL detail response is not valid JSON.", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalStateException("KORAIL detail response is missing required field " + field + ".");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : "";
    }

    private static boolean isConfiguredDateTime(String date, String time) {
        if (date == null || time == null || date.isBlank() || time.isBlank() || "00000000".equals(date)) {
            return false;
        }
        try {
            LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    public record ParsedDetail(
            Detail detail,
            List<Attachment> attachments,
            List<LongTextLine> noticeLines
    ) {
        public String noticeText() {
            return noticeLines.stream()
                    .map(LongTextLine::text)
                    .collect(Collectors.joining("\n"));
        }

        public List<BidAttachmentDto> bidAttachments() {
            return attachments.stream().map(Attachment::toBidAttachment).toList();
        }
    }

    /** Source values are kept separate because every date/time pair has different semantics. */
    public record Detail(
            String noticeNo,
            String round,
            String title,
            String statusCode,
            String statusName,
            String noticeType,
            String contractMethod,
            String awardMethod,
            String bidMethod,
            String publishedDate,
            String publishedTime,
            String bidStartDate,
            String bidStartTime,
            String bidEndDate,
            String bidEndTime,
            String applicationDate,
            String applicationTime,
            String technicalSubmissionDate,
            String technicalSubmissionTime,
            String openingDate,
            String openingTime,
            String contractPeriod,
            String nextNoticeNo,
            String nextRound
    ) {
        public boolean publishedDateTimeSet() {
            return isConfiguredDateTime(publishedDate, publishedTime);
        }

        public boolean bidStartDateTimeSet() {
            return isConfiguredDateTime(bidStartDate, bidStartTime);
        }

        public boolean bidEndDateTimeSet() {
            return isConfiguredDateTime(bidEndDate, bidEndTime);
        }

        public boolean applicationDateTimeSet() {
            return isConfiguredDateTime(applicationDate, applicationTime);
        }

        public boolean technicalSubmissionDateTimeSet() {
            return isConfiguredDateTime(technicalSubmissionDate, technicalSubmissionTime);
        }

        public boolean openingDateTimeSet() {
            return isConfiguredDateTime(openingDate, openingTime);
        }
    }

    public record Attachment(
            String fileName,
            BigInteger fileIndex,
            String documentNumber,
            String documentType,
            String documentVersion,
            String fileId
    ) {
        public BidAttachmentDto toBidAttachment() {
            return new BidAttachmentDto(fileName, "", documentType, "NOT_ANALYZED");
        }
    }

    public record LongTextLine(String format, String text) {
    }
}
