package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 입찰공고의 참가조건 정보를 한 번에 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BidQualificationDto {

    // 입찰공고번호
    private String bidNtceNo;

    // 면허/업종 제한
    private String licenseLimit;

    // 참가가능지역
    private String participationRegion;

    // 낙찰방법명
    private String sucsfbidMthdNm;

    // 낙찰방법코드
    private String sucsfbidMthdCd;

    // 실적경쟁여부
    private String arsltCmptYn;

    // PQ심사여부
    private String pqEvalYn;

    // TP심사여부
    private String tpEvalYn;

    // 공동수급협정서 접수방식
    private String cmmnSpldmdAgrmntRcptdocMethd;
}
