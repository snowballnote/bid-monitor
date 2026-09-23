package com.comhu.bidmonitor.bid.source;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 나라장터 OpenAPI에 포함되지 않는 연계기관 공고를 동일한 판정 흐름에 공급하는 확장점이다.
 * 출처별 수집기는 원천 필드만 공통 DTO로 변환하고, 낙찰방법 및 검토상태 판정은 기존 서비스에 맡긴다.
 */
public interface BidCandidateCollector {

    default String sourceCode() {
        return "ADDITIONAL";
    }

    default boolean executionEnabled() {
        return true;
    }

    List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate);
}
