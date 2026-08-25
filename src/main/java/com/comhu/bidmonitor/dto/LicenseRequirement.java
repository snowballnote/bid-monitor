package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 나라장터 면허제한정보의 개별 면허 조건을 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LicenseRequirement {

    // 면허제한 항목의 순번(lmtSno)
    private String sequence;

    // 면허제한명에서 분리한 업종·면허 코드
    private String licenseCode;

    // 면허제한명에서 분리한 업종·면허 이름
    private String licenseName;

    // 나라장터 API가 반환한 원본 면허제한명
    private String rawLicenseLimitName;
}
