package com.comhu.bidmonitor.bid.persistence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BidQualificationNoticeMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BidQualificationNoticeMapper mapper =
            new BidQualificationNoticeMapper(new BidNoticeContentHasher());

    @Test
    void storesDistinctLicenseCodesInObservedGroupOrder() throws Exception {
        BidQualificationDto source = candidate();
        source.setLicenseGroups(List.of(
                new LicenseRequirementGroup("1", List.of(
                        requirement("1", "6146"),
                        requirement("2", "6525")
                )),
                new LicenseRequirementGroup("2", List.of(
                        requirement("1", "6146"),
                        requirement("2", " 1468 "),
                        requirement("3", "invalid")
                ))
        ));

        BidNotice notice = mapper.map(source, Instant.parse("2026-10-21T00:00:00Z"));
        JsonNode analysis = objectMapper.readTree(notice.getAnalysisResult());

        assertEquals(List.of("6146", "6525", "1468"),
                objectMapper.convertValue(analysis.path("licenseCodes"), List.class));
    }

    @Test
    void storesEmptyCodeListWhenStructuredLicenseEvidenceIsMissing() throws Exception {
        BidNotice notice = mapper.map(candidate(), Instant.parse("2026-10-21T00:00:00Z"));
        JsonNode analysis = objectMapper.readTree(notice.getAnalysisResult());

        assertEquals(0, analysis.path("licenseCodes").size());
    }

    private BidQualificationDto candidate() {
        BidQualificationDto source = new BidQualificationDto();
        source.setSourceCode("G2B");
        source.setSourceNoticeId("notice-1");
        source.setBidNtceNm("테스트 공고");
        return source;
    }

    private LicenseRequirement requirement(String sequence, String code) {
        return new LicenseRequirement(sequence, code, null, null);
    }
}
