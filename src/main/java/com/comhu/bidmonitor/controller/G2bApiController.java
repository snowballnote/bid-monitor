package com.comhu.bidmonitor.controller;

import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.G2bApiService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 나라장터 API 호출을 테스트하기 위한 REST 컨트롤러
@RestController
@RequestMapping("/api")
public class G2bApiController {

    private static final Set<String> DEFAULT_ALLOWED_LICENSE_CODES = Set.of("6146", "1468");

    private final G2bApiService g2bApiService;

    // 생성자 주입으로 나라장터 API 서비스 의존성을 전달받는다.
    public G2bApiController(G2bApiService g2bApiService) {
        this.g2bApiService = g2bApiService;
    }

    // GET /api/bids 요청 시 나라장터 용역 입찰공고 조회 결과를 그대로 반환한다.
    @GetMapping("/bids")
    public String getBidList() {
        return g2bApiService.getBidList();
    }

    // GET /api/bids/dto 요청 시 입찰공고 DTO 목록을 반환한다.
    @GetMapping("/bids/dto")
    public List<BidDto> getBidDtoList() {
        return g2bApiService.getBidDtoList();
    }

    // 대리님 요청 조건에 맞는 공고만 반환한다.
    @GetMapping("/bids/target")
    public List<BidDto> getTargetBidList() {
        return g2bApiService.getTargetBidList();
    }

    // 특정 입찰공고의 면허제한정보를 테스트로 확인하기 위한 API이다.
    @GetMapping("/bids/{bidNtceNo}/license")
    public String getLicenseLimit(@PathVariable String bidNtceNo) {
        return g2bApiService.getLicenseLimit(bidNtceNo);
    }

    // 특정 입찰공고의 참가가능지역정보를 테스트로 확인하기 위한 API이다.
    @GetMapping("/bids/{bidNtceNo}/region")
    public String getParticipationRegion(@PathVariable String bidNtceNo) {
        return g2bApiService.getParticipationRegion(bidNtceNo);
    }

    // 특정 입찰공고의 참가조건을 한 번에 확인하기 위한 통합 테스트 API이다.
    @GetMapping("/bids/{bidNtceNo}/qualification")
    public BidQualificationDto getBidQualification(
            @PathVariable String bidNtceNo,
            @RequestParam(required = false) String allowedLicenseCodes
    ) {
        return g2bApiService.getBidQualification(bidNtceNo, parseAllowedLicenseCodes(allowedLicenseCodes));
    }

    // 오늘 대상 공고 전체의 참가조건 자동 판정 결과를 확인하기 위한 API이다.
    @GetMapping("/bids/target/qualification")
    public List<BidQualificationDto> getTargetBidQualificationList(
            @RequestParam(required = false) String allowedLicenseCodes
    ) {
        return g2bApiService.getTargetBidQualificationList(parseAllowedLicenseCodes(allowedLicenseCodes));
    }

    // 지정한 기간의 대상 공고에 대한 참가조건 자동 판정 결과를 조회하는 API이다.
    @GetMapping("/bids/target/qualification/range")
    public List<BidQualificationDto> getTargetBidQualificationListByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String allowedLicenseCodes
    ) {
        return g2bApiService.getTargetBidQualificationList(
                startDate,
                endDate,
                parseAllowedLicenseCodes(allowedLicenseCodes)
        );
    }

    /**
     * 쉼표로 전달된 허용 업종코드를 중복 없는 Set으로 변환하고, 값이 없으면 기본값을 사용한다.
     */
    private Set<String> parseAllowedLicenseCodes(String allowedLicenseCodes) {
        if (allowedLicenseCodes == null || allowedLicenseCodes.isBlank()) {
            return DEFAULT_ALLOWED_LICENSE_CODES;
        }

        Set<String> parsedCodes = new LinkedHashSet<>();
        for (String code : allowedLicenseCodes.split(",")) {
            String trimmedCode = code.trim();
            if (!trimmedCode.isEmpty()) {
                parsedCodes.add(trimmedCode);
            }
        }
        return parsedCodes.isEmpty() ? DEFAULT_ALLOWED_LICENSE_CODES : parsedCodes;
    }
}
