package com.comhu.bidmonitor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 동일한 제한그룹번호에 속한 면허 조건들을 담는 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LicenseRequirementGroup {

    // 나라장터 면허제한정보의 제한그룹번호(lmtGrpNo)
    private String groupNo;

    // 같은 그룹에 속하며 제한순번 순으로 정렬된 면허 조건 목록
    private List<LicenseRequirement> requirements;
}
