package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.classifier.BidAwardMethodResult;
import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class G2bApiServiceBroadCandidateTests {

    @Test
    void linkedAgencyNoticeMissingFromListMethodFilterStillReachesDetailClassification() {
        G2bApiService service = new LinkedAgencyFixtureService();

        List<BidQualificationDto> result = service.getTargetBidQualificationList(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1),
                Set.of("6146", "1468")
        );

        assertEquals(1, result.size());
        assertEquals("QUALIFICATION_REVIEW", result.getFirst().getAwardMethodCategory());
        assertEquals("CONFIRMED", result.getFirst().getAwardMethodStatus());
        assertEquals("STRUCTURED_DETAIL", result.getFirst().getAwardMethodSource());
        assertEquals("G2B", result.getFirst().getSourceCode());
        assertEquals("LINKED-2026-0001", result.getFirst().getSourceNoticeId());
    }

    @Test
    void legacyFastTargetFilterRemainsAvailable() {
        G2bApiService service = new FastFilterFixtureService();

        List<BidDto> result = service.getTargetBidList(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1)
        );

        assertEquals(List.of("FAST-QUALIFIED"), result.stream().map(BidDto::getBidNtceNo).toList());
    }

    /** 목록에는 낙찰방법이 없지만 공고번호 직접조회에는 적격심사제가 있는 연계기관 응답을 재현한다. */
    private static final class LinkedAgencyFixtureService extends G2bApiService {

        private final BidAwardMethodClassifier classifier = new BidAwardMethodClassifier();

        @Override
        public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
            BidDto listItem = new BidDto();
            listItem.setBidNtceNo("LINKED-2026-0001");
            listItem.setBidNtceNm("연계기관 정보시스템 감리 용역");
            listItem.setSucsfbidMthdNm("");
            listItem.setSucsfbidMthdCd("");
            return List.of(listItem);
        }

        @Override
        public BidQualificationDto getBidQualification(String bidNtceNo, Set<String> allowedLicenseCodes) {
            BidQualificationDto detail = new BidQualificationDto();
            detail.setBidNtceNo(bidNtceNo);
            detail.setSucsfbidMthdNm("적격심사제");
            BidAwardMethodResult classified = classifier.classify(detail);
            detail.setAwardMethodCategory(classified.category().name());
            detail.setAwardMethodStatus(classified.status().name());
            detail.setAwardMethodReason(classified.reason());
            detail.setAwardMethodSource(classified.source().name());
            return detail;
        }
    }

    private static final class FastFilterFixtureService extends G2bApiService {

        @Override
        public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
            BidDto target = new BidDto();
            target.setBidNtceNo("FAST-QUALIFIED");
            target.setSucsfbidMthdCd("낙030001");
            target.setSucsfbidMthdNm("적격심사");

            BidDto unknown = new BidDto();
            unknown.setBidNtceNo("DETAIL-REQUIRED");
            unknown.setSucsfbidMthdCd("");
            unknown.setSucsfbidMthdNm("");
            return List.of(target, unknown);
        }
    }
}
