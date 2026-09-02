package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

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

    // 나라장터 입찰공고 상세 URL
    private String bidNtceDtlUrl;

    // 공고문, 과업지시서, 제안요청서 등 나라장터 첨부문서 목록
    private List<BidAttachmentDto> attachments;

    // 공고 전체 첨부파일을 분석한 외부사이트 확인 상태(REQUIRED, REFERENCE, NOT_DETECTED, UNKNOWN)
    private String externalCheckStatus = "UNKNOWN";

    // 외부 기관 사이트를 추가로 확인해야 하는지 여부(판단 불가는 null)
    private Boolean externalSiteCheckRequired;

    // 첨부문서에서 탐지한 나라장터 외부 URL의 중복 제거 목록
    private List<String> externalSiteUrls = new ArrayList<>();

    // 공고 단위 외부사이트 확인 상태를 결정한 사유
    private String externalCheckReason = "";

    // 면허/업종 제한
    private String licenseLimit;

    // 제한그룹번호와 제한순번을 유지한 구조화된 면허 조건 목록
    private List<LicenseRequirementGroup> licenseGroups;

    // 참가가능지역
    private String participationRegion;

    // 낙찰방법명
    private String sucsfbidMthdNm;

    // 낙찰방법코드
    private String sucsfbidMthdCd;

    // 상세 응답의 낙찰방법 적용기준
    private String sucsfbidMthdAppStd;

    // 자체 낙찰방법 분류(QUALIFICATION_REVIEW, SMALL_AMOUNT_ESTIMATE, OTHER, UNKNOWN)
    private String awardMethodCategory = "UNKNOWN";

    // 자체 판정 강도(CONFIRMED, LIKELY, NOT_DETECTED, UNKNOWN)
    private String awardMethodStatus = "UNKNOWN";

    // 자체 판정에 사용한 사람이 확인할 수 있는 근거
    private String awardMethodReason = "";

    // 가장 강한 판정 근거의 출처(STRUCTURED_DETAIL, ATTACHMENT_DOCUMENT, NOT_AVAILABLE)
    private String awardMethodSource = "NOT_AVAILABLE";

    // 실적경쟁여부
    private String arsltCmptYn;

    // PQ심사여부
    private String pqEvalYn;

    // TP심사여부
    private String tpEvalYn;

    // 공동수급협정서 접수방식
    private String cmmnSpldmdAgrmntRcptdocMethd;

    // 공고 검토 상태(검토대상, 제외, 추가확인필요)
    private String reviewStatus;

    // 검토 상태를 판정한 사유
    private String reviewReason;

    /**
     * 자동 판정 필드 추가 전의 기존 생성 호출과의 호환을 유지한다.
     */
    public BidQualificationDto(
            String bidNtceNo,
            String licenseLimit,
            String participationRegion,
            String sucsfbidMthdNm,
            String sucsfbidMthdCd,
            String arsltCmptYn,
            String pqEvalYn,
            String tpEvalYn,
            String cmmnSpldmdAgrmntRcptdocMethd
    ) {
        this.bidNtceNo = bidNtceNo;
        this.licenseLimit = licenseLimit;
        this.participationRegion = participationRegion;
        this.sucsfbidMthdNm = sucsfbidMthdNm;
        this.sucsfbidMthdCd = sucsfbidMthdCd;
        this.arsltCmptYn = arsltCmptYn;
        this.pqEvalYn = pqEvalYn;
        this.tpEvalYn = tpEvalYn;
        this.cmmnSpldmdAgrmntRcptdocMethd = cmmnSpldmdAgrmntRcptdocMethd;
    }
}
