package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 나라장터 입찰공고에서 화면 또는 서비스에 필요한 항목만 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BidDto {

    // 입찰공고번호
    private String bidNtceNo;

    // 입찰공고명
    private String bidNtceNm;

    // 공고기관명
    private String ntceInsttNm;

    // 입찰공고일시
    private String bidNtceDt;

    // 입찰마감일시
    private String bidClseDt;

    // 배정예산금액
    private String asignBdgtAmt;

    // 낙찰자결정방법명
    private String sucsfbidMthdNm;

    // 입찰공고 상세조회 URL
    private String bidNtceDtlUrl;

}
