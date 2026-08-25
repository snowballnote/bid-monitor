package com.comhu.bidmonitor.controller;

import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.service.G2bApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 나라장터 API 호출을 테스트하기 위한 REST 컨트롤러
@RestController
@RequestMapping("/api")
public class G2bApiController {

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
    public BidQualificationDto getBidQualification(@PathVariable String bidNtceNo) {
        return g2bApiService.getBidQualification(bidNtceNo);
    }

    // 오늘 대상 공고 전체의 참가조건 자동 판정 결과를 확인하기 위한 API이다.
    @GetMapping("/bids/target/qualification")
    public List<BidQualificationDto> getTargetBidQualificationList() {
        return g2bApiService.getTargetBidQualificationList();
    }
}
