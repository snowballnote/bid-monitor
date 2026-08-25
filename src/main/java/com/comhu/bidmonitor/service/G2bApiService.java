package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// 나라장터(G2B) OpenAPI 호출을 담당하는 서비스 클래스
@Service
public class G2bApiService {

    // application.properties에 설정한 나라장터 API 기본 주소를 가져옴
    @Value("${g2b.api.base-url}")
    private String baseUrl;

    // Windows 환경변수에 저장된 나라장터 API 인증키를 가져옴
    // 실제 인증키가 GitHub에 노출되지 않도록 소스코드에는 직접 작성하지 않음
    @Value("${g2b.api.service-key}")
    private String serviceKey;

    /**
     * 나라장터 용역 입찰공고 목록을 테스트용으로 조회한다.
     */
    public String getBidList() {
        // 실행 당일의 입찰공고를 조회하기 위해 현재 날짜를 yyyyMMdd 형식으로 만든다.
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //String today = "20260713"; // 테스트
        String inquiryStartDateTime = today + "0000";
        String inquiryEndDateTime = today + "2359";

        // 브라우저에서 정상 호출된 URL과 동일한 형태로 전체 요청 주소를 직접 만든다.
        // ServiceKey가 이미 URL Encoding된 값이므로 RestClient가 다시 인코딩하지 않도록
        // 최종 문자열을 URI 객체로 변환해 그대로 전달한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServcPPSSrch"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=1"
                + "&inqryBgnDt=" + inquiryStartDateTime
                + "&inqryEndDt=" + inquiryEndDateTime
                // 업종코드 6146에 해당하는 용역 입찰공고만 조회한다.
                + "&indstrytyCd=6146";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 사용자가 지정한 기간의 용역 감리 입찰공고 목록을 조회한다.
     */
    public String getBidList(LocalDate startDate, LocalDate endDate) {
        String inquiryStartDateTime = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "0000";
        String inquiryEndDateTime = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "2359";

        // 기존 목록 조회와 같은 용역 공고 오퍼레이션에서 기간만 사용자 입력값으로 지정한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServcPPSSrch"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=100"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=1"
                + "&inqryBgnDt=" + inquiryStartDateTime
                + "&inqryEndDt=" + inquiryEndDateTime
                + "&indstrytyCd=6146";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 특정 입찰공고의 면허제한정보를 테스트 용도로 조회한다.
     */
    public String getLicenseLimit(String bidNtceNo) {
        // 면허제한정보는 해당 공고에 참여하기 위해 필요한 업종·면허 자격을 확인하는 데 사용한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoLicenseLimit"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 특정 입찰공고의 참가가능지역정보를 테스트 용도로 조회한다.
     */
    public String getParticipationRegion(String bidNtceNo) {
        // 참가가능지역정보는 입찰 참여가 허용되는 지역을 확인하여 지역 제한 여부를 판단하는 데 사용한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoPrtcptPsblRgn"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();

        return restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);
    }

    /**
     * 입찰공고의 면허, 참가가능지역 및 낙찰 관련 참가조건을 하나의 DTO로 조합한다.
     */
    public BidQualificationDto getBidQualification(String bidNtceNo) {
        // 공고번호 직접조회로 과거 공고를 포함한 기본 입찰정보를 가져온다.
        BidDto bidDto = getBidDtoByBidNtceNo(bidNtceNo);

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            // 면허제한 응답의 면허제한명들을 쉼표로 연결하며, 제한이 없으면 빈 문자열로 둔다.
            JsonNode licenseBody = objectMapper.readTree(getLicenseLimit(bidNtceNo))
                    .path("response")
                    .path("body");
            String licenseLimit = licenseBody.path("totalCount").asInt() == 0
                    ? ""
                    : getJoinedItemValues(licenseBody.path("items"), "lcnsLmtNm");

            // 명세의 참가가능지역명(prtcptPsblRgnNm)을 읽어 여러 지역은 쉼표로 연결한다.
            JsonNode regionBody = objectMapper.readTree(getParticipationRegion(bidNtceNo))
                    .path("response")
                    .path("body");
            String participationRegion = regionBody.path("totalCount").asInt() == 0
                    ? "제한없음"
                    : getJoinedItemValues(regionBody.path("items"), "prtcptPsblRgnNm");

            // 기본 공고정보와 상세 참가조건을 함께 담는다.
            BidQualificationDto qualification = new BidQualificationDto(
                    bidNtceNo,
                    licenseLimit,
                    participationRegion,
                    bidDto.getSucsfbidMthdNm(),
                    bidDto.getSucsfbidMthdCd(),
                    bidDto.getArsltCmptYn(),
                    bidDto.getPqEvalYn(),
                    bidDto.getTpEvalYn(),
                    bidDto.getCmmnSpldmdAgrmntRcptdocMethd()
            );

            // 이미 직접조회한 BidDto의 기본 공고정보를 함께 설정한다.
            qualification.setBidNtceNm(bidDto.getBidNtceNm());
            qualification.setNtceInsttNm(bidDto.getNtceInsttNm());
            qualification.setBidNtceDt(bidDto.getBidNtceDt());
            qualification.setBidClseDt(bidDto.getBidClseDt());
            qualification.setAsignBdgtAmt(bidDto.getAsignBdgtAmt());
            qualification.setBidNtceDtlUrl(bidDto.getBidNtceDtlUrl());

            // 조합한 참가조건을 기준으로 자동 검토 상태와 판정 사유를 설정한다.
            applyReviewResult(qualification);
            return qualification;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("참가조건 API 응답을 JSON으로 처리할 수 없습니다.", e);
        }
    }

    /**
     * 참가조건을 기준으로 공고의 자동 검토 상태와 사람이 확인할 판정 사유를 설정한다.
     */
    private void applyReviewResult(BidQualificationDto qualification) {
        String sucsfbidMthdCd = getSafeValue(qualification.getSucsfbidMthdCd());
        String sucsfbidMthdNm = getSafeValue(qualification.getSucsfbidMthdNm());

        // 협상에 의한 계약은 다른 조건과 관계없이 검토 대상에서 제외한다.
        if ("낙030005".equals(sucsfbidMthdCd) || sucsfbidMthdNm.contains("협상")) {
            qualification.setReviewStatus("제외");
            qualification.setReviewReason("협상에 의한 계약으로 대상 제외");
            return;
        }

        boolean smallAmountEstimate = "낙030029".equals(sucsfbidMthdCd)
                || sucsfbidMthdNm.contains("소액수의견적");
        boolean qualificationReview = "낙030001".equals(sucsfbidMthdCd)
                && sucsfbidMthdNm.contains("적격심사");

        // 소액수의견적 또는 적격심사제에 해당하지 않으면 자동 판정만으로는 검토 여부를 확정할 수 없다.
        if (!smallAmountEstimate && !qualificationReview) {
            qualification.setReviewStatus("추가확인필요");
            qualification.setReviewReason("낙찰방법이 검토대상 기준에 해당하는지 확인 필요");
            return;
        }

        List<String> additionalCheckReasons = new ArrayList<>();
        String licenseLimit = getSafeValue(qualification.getLicenseLimit());
        String participationRegion = getSafeValue(qualification.getParticipationRegion());

        // 검토대상 공고라도 면허, 지역 및 심사 조건이 있으면 추가 확인이 필요하다.
        if (!licenseLimit.contains("6146")) {
            additionalCheckReasons.add(licenseLimit.isEmpty()
                    ? "6146 면허조건 확인 필요"
                    : "6146 면허조건 확인 필요: " + licenseLimit);
        }
        if (!"제한없음".equals(participationRegion)) {
            additionalCheckReasons.add(participationRegion.isEmpty()
                    ? "지역제한 조건 확인 필요"
                    : "지역제한 조건 확인 필요: " + participationRegion);
        }
        if ("Y".equals(getSafeValue(qualification.getArsltCmptYn()))) {
            additionalCheckReasons.add("실적경쟁 조건 확인 필요");
        }
        if ("Y".equals(getSafeValue(qualification.getPqEvalYn()))) {
            additionalCheckReasons.add("PQ심사 조건 확인 필요");
        }
        if ("Y".equals(getSafeValue(qualification.getTpEvalYn()))) {
            additionalCheckReasons.add("TP심사 조건 확인 필요");
        }

        if (!additionalCheckReasons.isEmpty()) {
            qualification.setReviewStatus("추가확인필요");
            qualification.setReviewReason(String.join(", ", additionalCheckReasons));
            return;
        }

        qualification.setReviewStatus("검토대상");
        qualification.setReviewReason((smallAmountEstimate ? "소액수의견적" : "적격심사제")
                + ", 6146 면허조건, 지역제한 없음");
    }

    /**
     * API 응답의 누락값을 빈 문자열로 바꿔 null 비교와 문자열 검사 시 예외를 방지한다.
     */
    private String getSafeValue(String value) {
        return value == null ? "" : value;
    }

    /**
     * 용역 입찰공고를 공고번호로 직접 조회해 BidDto로 변환한다.
     */
    private BidDto getBidDtoByBidNtceNo(String bidNtceNo) {
        // inqryDiv=2는 나라장터 명세에서 입찰공고번호 기준 조회를 의미한다.
        String requestUrl = baseUrl
                + "/getBidPblancListInfoServc"
                + "?ServiceKey=" + serviceKey
                + "&numOfRows=10"
                + "&pageNo=1"
                + "&type=json"
                + "&inqryDiv=2"
                + "&bidNtceNo=" + bidNtceNo
                + "&bidNtceOrd=000";

        RestClient restClient = RestClient.create();
        String responseBody = restClient.get()
                .uri(URI.create(requestUrl))
                .retrieve()
                .body(String.class);

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            JsonNode items = objectMapper.readTree(responseBody)
                    .path("response")
                    .path("body")
                    .path("items");

            // 직접조회 결과에서 요청한 공고번호와 일치하는 공고를 선택한다.
            for (JsonNode item : items) {
                if (bidNtceNo.equals(item.path("bidNtceNo").asText())) {
                    return new BidDto(
                            item.path("bidNtceNo").asText(),
                            item.path("bidNtceNm").asText(),
                            item.path("ntceInsttNm").asText(),
                            item.path("bidNtceDt").asText(),
                            item.path("bidClseDt").asText(),
                            item.path("asignBdgtAmt").asText(),
                            item.path("sucsfbidMthdNm").asText(),
                            item.path("bidNtceDtlUrl").asText(),
                            item.path("sucsfbidMthdCd").asText(),
                            item.path("techAbltEvlRt").asText(),
                            item.path("bidPrceEvlRt").asText(),
                            item.path("sucsfbidMthdAppStd").asText(),
                            item.path("arsltCmptYn").asText(),
                            item.path("pqEvalYn").asText(),
                            item.path("tpEvalYn").asText(),
                            item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
                    );
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("입찰공고 직접조회 응답을 JSON으로 처리할 수 없습니다.", e);
        }

        throw new IllegalArgumentException("입력한 공고번호에 해당하는 입찰공고를 찾을 수 없습니다: " + bidNtceNo);
    }

    /**
     * 응답 items 배열의 지정한 필드값을 빈 값 없이 쉼표로 연결한다.
     */
    private String getJoinedItemValues(JsonNode items, String fieldName) {
        List<String> values = new ArrayList<>();

        for (JsonNode item : items) {
            String value = item.path(fieldName).asText();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }

        return String.join(",", values);
    }

    /**
     * 나라장터 응답의 입찰공고 항목만 BidDto 목록으로 변환한다.
     */
    public List<BidDto> getBidDtoList() {
        // 기존 API 호출 결과를 JSON 트리로 읽어 필요한 배열 경로만 선택한다.
        String responseBody = getBidList();
        ObjectMapper objectMapper = new ObjectMapper();
        List<BidDto> bidList = new ArrayList<>();

        try {
            JsonNode items = objectMapper.readTree(responseBody)
                    .path("response")
                    .path("body")
                    .path("items");

            // 각 입찰공고에서 화면에 필요한 필드만 BidDto에 담는다.
            for (JsonNode item : items) {
                bidList.add(new BidDto(
                        item.path("bidNtceNo").asText(),
                        item.path("bidNtceNm").asText(),
                        item.path("ntceInsttNm").asText(),
                        item.path("bidNtceDt").asText(),
                        item.path("bidClseDt").asText(),
                        item.path("asignBdgtAmt").asText(),
                        item.path("sucsfbidMthdNm").asText(),
                        item.path("bidNtceDtlUrl").asText(),
                        // 낙찰방법 및 심사 조건을 자동 판단에 사용할 수 있도록 함께 담는다.
                        item.path("sucsfbidMthdCd").asText(),
                        item.path("techAbltEvlRt").asText(),
                        item.path("bidPrceEvlRt").asText(),
                        item.path("sucsfbidMthdAppStd").asText(),
                        item.path("arsltCmptYn").asText(),
                        item.path("pqEvalYn").asText(),
                        item.path("tpEvalYn").asText(),
                        item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
                ));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("나라장터 API 응답을 JSON으로 변환할 수 없습니다.", e);
        }

        return bidList;
    }

    /**
     * 지정한 기간의 입찰공고 응답을 BidDto 목록으로 변환한다.
     */
    public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
        // 날짜 범위로 조회한 API 응답을 기존 DTO와 동일한 필드 구성으로 변환한다.
        return parseBidDtoList(getBidList(startDate, endDate));
    }

    /**
     * 나라장터 목록 응답의 items 배열을 BidDto 목록으로 변환한다.
     */
    private List<BidDto> parseBidDtoList(String responseBody) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<BidDto> bidList = new ArrayList<>();

        try {
            JsonNode items = objectMapper.readTree(responseBody)
                    .path("response")
                    .path("body")
                    .path("items");

            for (JsonNode item : items) {
                bidList.add(new BidDto(
                        item.path("bidNtceNo").asText(),
                        item.path("bidNtceNm").asText(),
                        item.path("ntceInsttNm").asText(),
                        item.path("bidNtceDt").asText(),
                        item.path("bidClseDt").asText(),
                        item.path("asignBdgtAmt").asText(),
                        item.path("sucsfbidMthdNm").asText(),
                        item.path("bidNtceDtlUrl").asText(),
                        item.path("sucsfbidMthdCd").asText(),
                        item.path("techAbltEvlRt").asText(),
                        item.path("bidPrceEvlRt").asText(),
                        item.path("sucsfbidMthdAppStd").asText(),
                        item.path("arsltCmptYn").asText(),
                        item.path("pqEvalYn").asText(),
                        item.path("tpEvalYn").asText(),
                        item.path("cmmnSpldmdAgrmntRcptdocMethd").asText()
                ));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("나라장터 API 응답을 JSON으로 변환할 수 없습니다.", e);
        }

        return bidList;
    }

    /**
     * 대리님 요청 조건에 맞는 낙찰방법의 공고만 조회한다.
     */
    public List<BidDto> getTargetBidList() {
        return getBidDtoList().stream()
                // 협상에 의한 계약(낙030005)은 대상에서 제외한다.
                .filter(bid -> !"낙030005".equals(bid.getSucsfbidMthdCd()))
                // 소액수의견적: 코드가 낙030029이거나 낙찰방법명에 소액수의견적이 포함된 공고
                .filter(bid -> "낙030029".equals(bid.getSucsfbidMthdCd())
                        || bid.getSucsfbidMthdNm().contains("소액수의견적")
                        // 적격심사제: 코드가 낙030001이면서 낙찰방법명에 적격심사가 포함된 공고
                        || ("낙030001".equals(bid.getSucsfbidMthdCd())
                        && bid.getSucsfbidMthdNm().contains("적격심사")))
                .collect(Collectors.toList());
    }

    /**
     * 지정한 기간의 공고 중 소액수의견적 또는 적격심사제 대상 공고만 반환한다.
     */
    public List<BidDto> getTargetBidList(LocalDate startDate, LocalDate endDate) {
        // 기간 조회 결과에 기존과 동일한 대상 공고 조건을 적용한다.
        return getBidDtoList(startDate, endDate).stream()
                .filter(this::isTargetBid)
                .collect(Collectors.toList());
    }

    /**
     * 기존 대상 공고 필터와 같은 소액수의견적 및 적격심사제 조건을 판단한다.
     */
    private boolean isTargetBid(BidDto bid) {
        String sucsfbidMthdCd = getSafeValue(bid.getSucsfbidMthdCd());
        String sucsfbidMthdNm = getSafeValue(bid.getSucsfbidMthdNm());

        return !"낙030005".equals(sucsfbidMthdCd)
                && ("낙030029".equals(sucsfbidMthdCd)
                || sucsfbidMthdNm.contains("소액수의견적")
                || ("낙030001".equals(sucsfbidMthdCd)
                && sucsfbidMthdNm.contains("적격심사")));
    }

    /**
     * 오늘의 대상 공고에 참가조건 자동 판정을 적용한 결과를 반환한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList() {
        // 기존 대상 필터로 소액수의견적 및 적격심사제 공고만 먼저 조회한다.
        return getTargetBidList().stream()
                // 공고별 상세 참가조건 조회와 기존 자동 판정 로직을 재사용한다.
                .map(BidDto::getBidNtceNo)
                .map(this::getBidQualification)
                .collect(Collectors.toList());
    }

    /**
     * 지정한 기간의 대상 공고에 기존 참가조건 자동 판정을 적용해 반환한다.
     */
    public List<BidQualificationDto> getTargetBidQualificationList(LocalDate startDate, LocalDate endDate) {
        // 기간별 대상 공고마다 기존 통합조회 및 자동 판정 로직을 재사용한다.
        return getTargetBidList(startDate, endDate).stream()
                .map(BidDto::getBidNtceNo)
                .map(this::getBidQualification)
                .collect(Collectors.toList());
    }
}
