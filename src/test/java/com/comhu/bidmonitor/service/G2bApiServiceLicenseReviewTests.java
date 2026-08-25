package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class G2bApiServiceLicenseReviewTests {

    private static final Set<String> DEFAULT_ALLOWED_CODES = Set.of("6146", "1468");

    private final G2bApiService service = new G2bApiService();

    @Test
    void defaultAllowedCodesAllowSingle6146Requirement() throws Exception {
        BidQualificationDto qualification = qualification(group("1", "6146"));

        applyReview(qualification, DEFAULT_ALLOWED_CODES);

        assertEquals("검토대상", qualification.getReviewStatus());
    }

    @Test
    void defaultAllowedCodesAllow6146And1468InSameGroup() throws Exception {
        BidQualificationDto qualification = qualification(group("1", "6146", "1468"));

        applyReview(qualification, DEFAULT_ALLOWED_CODES);

        assertEquals("검토대상", qualification.getReviewStatus());
    }

    @Test
    void unallowedCodeInAndGroupRequiresAdditionalReview() throws Exception {
        BidQualificationDto qualification = qualification(group("1", "6146", "3572"));

        applyReview(qualification, DEFAULT_ALLOWED_CODES);

        assertEquals("추가확인필요", qualification.getReviewStatus());
        assertEquals("추가 면허조건 확인 필요: 3572", qualification.getReviewReason());
    }

    @Test
    void oneAllowedOrGroupSatisfiesLicenseRequirement() throws Exception {
        BidQualificationDto qualification = qualification(
                group("1", "6146", "3572"),
                group("2", "6146", "1468")
        );

        applyReview(qualification, DEFAULT_ALLOWED_CODES);

        assertEquals("검토대상", qualification.getReviewStatus());
    }

    @Test
    void noticeWithout6146CannotBecomeReviewTarget() throws Exception {
        BidQualificationDto qualification = qualification(group("1", "1468"));

        applyReview(qualification, DEFAULT_ALLOWED_CODES);

        assertNotEquals("검토대상", qualification.getReviewStatus());
        assertEquals("추가확인필요", qualification.getReviewStatus());
        assertEquals("6146 면허조건 확인 필요", qualification.getReviewReason());
    }

    private BidQualificationDto qualification(LicenseRequirementGroup... groups) {
        BidQualificationDto qualification = new BidQualificationDto();
        qualification.setSucsfbidMthdCd("낙030029");
        qualification.setSucsfbidMthdNm("소액수의견적");
        qualification.setParticipationRegion("제한없음");
        qualification.setArsltCmptYn("N");
        qualification.setPqEvalYn("N");
        qualification.setTpEvalYn("N");
        qualification.setLicenseGroups(List.of(groups));
        return qualification;
    }

    private LicenseRequirementGroup group(String groupNo, String... licenseCodes) {
        List<LicenseRequirement> requirements = new ArrayList<>();
        for (int index = 0; index < licenseCodes.length; index++) {
            String code = licenseCodes[index];
            requirements.add(new LicenseRequirement(
                    String.valueOf(index + 1),
                    code,
                    "테스트 면허 " + code,
                    "테스트 면허 " + code + "/" + code
            ));
        }
        return new LicenseRequirementGroup(groupNo, requirements);
    }

    private void applyReview(BidQualificationDto qualification, Set<String> allowedCodes) throws Exception {
        Method method = G2bApiService.class.getDeclaredMethod(
                "applyReviewResult",
                BidQualificationDto.class,
                Set.class
        );
        method.setAccessible(true);
        method.invoke(service, qualification, allowedCodes);
    }
}
