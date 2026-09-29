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

    /** 등록 검토가 끝난 수집처와 명시적으로 연결할 수 있는 collector만 true를 반환한다. */
    default boolean registrationBindingSupported() {
        return false;
    }

    List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate);
}
