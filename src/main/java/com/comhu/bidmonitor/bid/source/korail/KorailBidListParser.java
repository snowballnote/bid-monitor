package com.comhu.bidmonitor.bid.source.korail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parses the KORAIL service-bid list response without making HTTP requests. */
public final class KorailBidListParser {

    private static final String LIST_TABLE = "ET_ZSMMEEBID0083";
    private final ObjectMapper objectMapper;

    public KorailBidListParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<Notice> parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalArgumentException("KORAIL 목록 응답이 비어 있습니다.");
        }

        JsonNode outer = parseJson(responseBody);
        if (!outer.isObject()) {
            throw new IllegalStateException("KORAIL 목록 응답의 최상위 구조가 올바르지 않습니다.");
        }

        JsonNode data = outer.path("data");
        if (!data.isTextual()) {
            throw new IllegalStateException("KORAIL 목록 응답에 JSON 문자열 data가 없습니다.");
        }
        String innerJson = data.textValue();
        if (innerJson.isBlank() || "error".equalsIgnoreCase(innerJson.strip())) {
            throw new IllegalStateException("KORAIL 목록 조회에 실패했습니다 (data 오류).");
        }

        JsonNode inner = parseJson(innerJson);
        JsonNode itab = inner.path("itab");
        if (!itab.isArray()) {
            throw new IllegalStateException("KORAIL 목록 응답에 itab 배열이 없습니다.");
        }

        JsonNode status = findValue(itab, "E_SUBRC");
        if (status == null || !status.isTextual() || !"S".equals(status.textValue())) {
            throw new IllegalStateException("KORAIL 목록 조회의 E_SUBRC가 성공 상태가 아닙니다.");
        }

        JsonNode rows = findValue(itab, LIST_TABLE);
        if (rows == null || !rows.isArray()) {
            throw new IllegalStateException("KORAIL 목록 응답에 공고 배열이 없습니다.");
        }

        List<Notice> notices = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw new IllegalStateException("KORAIL 공고 목록에 객체가 아닌 항목이 있습니다.");
            }
            notices.add(new Notice(
                    requiredText(row, "zzbidinv"),
                    requiredText(row, "zzstnum"),
                    text(row, "description"),
                    text(row, "zzbid_erdat"),
                    text(row, "zzbid_ertim"),
                    text(row, "zzbid_sdat"),
                    text(row, "zzbid_stim"),
                    text(row, "zzbid_edat"),
                    text(row, "zzbid_etim"),
                    text(row, "zzctrmethod_t"),
                    text(row, "zzwayofbidding_t"),
                    text(row, "zzbidinfo"),
                    text(row, "zzbidinv_new")
            ));
        }
        return List.copyOf(notices);
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("KORAIL 목록 응답 JSON 형식이 올바르지 않습니다.", exception);
        }
    }

    private static JsonNode findValue(JsonNode itab, String name) {
        for (JsonNode entry : itab) {
            if (name.equals(text(entry, "name"))) {
                return entry.get("value");
            }
        }
        return null;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalStateException("KORAIL 공고 필수 식별자가 누락되었습니다: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : "";
    }

    /** Values are kept as received. Status and method semantics are not inferred here. */
    public record Notice(
            String noticeNo,
            String round,
            String title,
            String publishedDate,
            String publishedTime,
            String bidStartDate,
            String bidStartTime,
            String bidEndDate,
            String bidEndTime,
            String contractMethod,
            String awardMethod,
            String statusCode,
            String nextNoticeNo
    ) { }
}
