package com.comhu.bidmonitor.bid.source.koreaexpressway;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 한국도로공사 전자조달의 공개 용역공고를 수집한다.
 * 이 출처의 공고는 나라장터 BidPublicInfoService 모집단에 없을 수 있으므로 별도 어댑터로 보완한다.
 */
@Component
@ConditionalOnProperty(
        name = "bid-source.korea-expressway.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class KoreaExpresswayBidCollector implements BidCandidateCollector {

    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter SOURCE_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String SERVICE_NOTICE_CLASS = "SV";
    private static final String QUALIFICATION_REVIEW_CODE = "STE";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    @Autowired
    public KoreaExpresswayBidCollector(
            @Value("${bid-source.korea-expressway.base-url:https://ebid.ex.co.kr}") String baseUrl
    ) {
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    @Override
    public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String csrfToken = openSession(client);
        JsonNode list = readJson(postJson(client, csrfToken, listEndpoint(), createListRequest(startDate, endDate)));
        if (!list.isArray()) {
            throw new IllegalStateException("한국도로공사 용역공고 목록 응답이 배열 형식이 아닙니다.");
        }

        List<BidQualificationDto> candidates = new ArrayList<>();
        for (JsonNode item : list) {
            // 출처 전체 용역 중 현재 Biz Assist의 업무 범위인 감리 후보만 상세조회한다.
            if (!containsSupervisionKeyword(item.path("noti_nm").asText())) {
                continue;
            }
            JsonNode detailResponse = readJson(postJson(
                    client,
                    csrfToken,
                    detailEndpoint(),
                    createDetailRequest(item)
            ));
            candidates.add(toQualification(item, detailResponse));
        }
        return candidates;
    }

    /** 공개 첫 화면에서 세션 쿠키와 CSRF 토큰을 함께 확보한다. */
    private String openSession(HttpClient client) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/default.do"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", browserUserAgent())
                .GET()
                .build();
        HttpResponse<String> response = send(client, request);
        requireSuccess(response, "한국도로공사 전자조달 초기 화면");

        Document document = Jsoup.parse(response.body());
        String csrfToken = document.select("meta[name=_csrf]").attr("content");
        if (csrfToken.isBlank()) {
            throw new IllegalStateException("한국도로공사 전자조달 응답에서 CSRF 토큰을 찾을 수 없습니다.");
        }
        return csrfToken;
    }

    private String postJson(HttpClient client, String csrfToken, String endpoint, JsonNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", browserUserAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-CSRF-TOKEN", csrfToken)
                .header("Origin", baseUrl)
                .header("Referer", baseUrl + "/default.do")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(client, request);
        requireSuccess(response, endpoint);
        return response.body();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("한국도로공사 전자조달 통신에 실패했습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("한국도로공사 전자조달 통신이 중단되었습니다.", e);
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " 호출에 실패했습니다. HTTP " + response.statusCode());
        }
    }

    private JsonNode createListRequest(LocalDate startDate, LocalDate endDate) {
        ObjectNode request = objectMapper.createObjectNode()
                .put("to_noti_date", endDate.format(REQUEST_DATE))
                .put("from_noti_date", startDate.format(REQUEST_DATE))
                .put("status", "E");
        request.putArray("limit_area");
        request.putArray("arr_status").add("EY").add("GY").add("FY");
        return request
                .putNull("pq_type")
                .putNull("bid_shpr1")
                .putNull("plrl_bid_yn")
                .putNull("dsgng_amt_start")
                .putNull("dsgng_amt_end")
                .putNull("cth_limit")
                .putNull("cnat_pbnt_amt_start")
                .putNull("cnat_pbnt_amt_end")
                .putNull("nwtc_ptnt_no1")
                .putNull("nwtc_ptnt_no2")
                .put("page", "noti")
                .put("noti_cls", SERVICE_NOTICE_CLASS);
    }

    private JsonNode createDetailRequest(JsonNode item) {
        return objectMapper.createObjectNode()
                .put("noti_no", item.path("noti_no").asText())
                .put("noti_id", item.path("noti_id").asText())
                .put("noti_cont_id", item.path("noti_cont_id").asText())
                .put("bid_no", item.path("bid_no").asInt())
                .put("bid_rev", item.path("bid_rev").asInt())
                .put("bid_nm", item.path("noti_nm").asText())
                .put("prog_sts", item.path("prog_sts").asText())
                .put("noti_cls", SERVICE_NOTICE_CLASS)
                .put("page", "noti");
    }

    /** 출처 고유 필드를 기존 공통 판정 DTO로 옮기되 판단 자체는 수행하지 않는다. */
    BidQualificationDto toQualification(JsonNode listItem, JsonNode detailResponse) {
        JsonNode detail = detailResponse.path("detailData");
        if (detail.isMissingNode() || !detail.isObject()) {
            throw new IllegalStateException("한국도로공사 공고 상세 응답에 detailData가 없습니다.");
        }

        BidQualificationDto qualification = new BidQualificationDto();
        qualification.setBidNtceNo(formatNoticeNumber(detail.path("noti_no").asText()));
        qualification.setBidNtceNm(detail.path("noti_nm").asText(listItem.path("noti_nm").asText()));
        qualification.setNtceInsttNm("한국도로공사");
        qualification.setBidNtceDt(formatSourceDateTime(detail.path("bid_start_dt").asText()));
        qualification.setBidClseDt(formatSourceDateTime(detail.path("bid_end_dt").asText()));
        qualification.setAsignBdgtAmt(detail.path("dsgng_amt").asText());
        qualification.setBidNtceDtlUrl(createSourceDetailUrl(listItem));
        qualification.setAttachments(createAttachments(detailResponse.path("fileAttList")));
        qualification.setLicenseLimit(detail.path("bid_prtc_lcs").asText());
        qualification.setLicenseGroups(List.of());
        qualification.setParticipationRegion("");
        qualification.setSucsfbidMthdCd("");
        qualification.setSucsfbidMthdNm(mapAwardMethod(detail.path("stl_terms").asText()));
        qualification.setSucsfbidMthdAppStd(detail.path("qual_insp_bas").asText());
        qualification.setArsltCmptYn("");
        qualification.setPqEvalYn(detail.path("pq_yn").asText());
        qualification.setTpEvalYn("");
        qualification.setCmmnSpldmdAgrmntRcptdocMethd("");
        return qualification;
    }

    private List<BidAttachmentDto> createAttachments(JsonNode files) {
        if (!files.isArray()) {
            return List.of();
        }
        List<BidAttachmentDto> attachments = new ArrayList<>();
        for (JsonNode file : files) {
            String fileName = firstText(file, "file_nm", "file_name", "att_nm", "orgn_file_nm");
            String fileUrl = firstText(file, "file_url", "download_url", "url");
            if (!fileName.isBlank() || !fileUrl.isBlank()) {
                attachments.add(new BidAttachmentDto(fileName, fileUrl, "기타", "NOT_ANALYZED"));
            }
        }
        return attachments;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String mapAwardMethod(String sourceCode) {
        return QUALIFICATION_REVIEW_CODE.equalsIgnoreCase(sourceCode) ? "적격심사제" : "";
    }

    private String formatNoticeNumber(String noticeNumber) {
        return noticeNumber.isBlank() ? "" : noticeNumber + "-00";
    }

    /** 공개 목록 응답의 식별자로 새 세션에서도 곧바로 해당 공고 상세를 여는 URL을 만든다. */
    private String createSourceDetailUrl(JsonNode listItem) {
        String notiId = listItem.path("noti_id").asText();
        String notiContentId = listItem.path("noti_cont_id").asText();
        String noticeNumber = listItem.path("noti_no").asText();
        String bidNumber = listItem.path("bid_no").asText();
        String bidRevision = listItem.path("bid_rev").asText();
        if (List.of(notiId, notiContentId, noticeNumber, bidNumber, bidRevision)
                .stream().anyMatch(String::isBlank)) {
            return "";
        }

        return baseUrl + "/default.do?menuId=NPRO12001"
                + "&noti_id=" + encodeQueryValue(notiId)
                + "&noti_cont_id=" + encodeQueryValue(notiContentId)
                + "&noti_no=" + encodeQueryValue(noticeNumber)
                + "&bid_no=" + encodeQueryValue(bidNumber)
                + "&bid_rev=" + encodeQueryValue(bidRevision);
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String formatSourceDateTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(value, SOURCE_DATE_TIME).format(DISPLAY_DATE_TIME);
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private boolean containsSupervisionKeyword(String title) {
        return title != null && title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).contains("감리");
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("한국도로공사 전자조달 응답을 JSON으로 처리할 수 없습니다.", e);
        }
    }

    private String listEndpoint() {
        return baseUrl + "/ui/sp/expro/bidnoti/findListBidNoti.do";
    }

    private String detailEndpoint() {
        return baseUrl + "/ui/sp/expro/bidnoti/findInfoNotiDetail.do";
    }

    private String browserUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("한국도로공사 공고 조회 시작일과 종료일을 확인해주세요.");
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("한국도로공사 전자조달 기본 URL이 비어 있습니다.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
