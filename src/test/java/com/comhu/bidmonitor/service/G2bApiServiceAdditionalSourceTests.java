package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class G2bApiServiceAdditionalSourceTests {

    @Test
    void additionalSourceCandidateUsesSameClassifierAndReviewPipeline() {
        BidCandidateCollector collector = (startDate, endDate) -> List.of(linkedAgencyCandidate());
        G2bApiService service = new EmptyG2bFixtureService(collector);

        List<BidQualificationDto> result = service.getTargetBidQualificationList(
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 27),
                Set.of("6146", "1468")
        );

        assertEquals(1, result.size());
        BidQualificationDto notice = result.getFirst();
        assertEquals("202608222-00", notice.getBidNtceNo());
        assertEquals("QUALIFICATION_REVIEW", notice.getAwardMethodCategory());
        assertEquals("CONFIRMED", notice.getAwardMethodStatus());
        assertEquals("STRUCTURED_DETAIL", notice.getAwardMethodSource());
        assertEquals("추가확인필요", notice.getReviewStatus());
    }

    private static BidQualificationDto linkedAgencyCandidate() {
        BidQualificationDto candidate = new BidQualificationDto();
        candidate.setBidNtceNo("202608222-00");
        candidate.setBidNtceNm("AI기술을 활용한 통행료정보시스템 고도화 감리용역");
        candidate.setSucsfbidMthdNm("적격심사제");
        candidate.setLicenseGroups(List.of());
        candidate.setAttachments(List.of());
        return candidate;
    }

    private static final class EmptyG2bFixtureService extends G2bApiService {

        private EmptyG2bFixtureService(BidCandidateCollector collector) {
            super(new BidAwardMethodClassifier(), List.of(collector));
        }

        @Override
        public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
            return List.of();
        }
    }
}
