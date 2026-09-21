package com.comhu.bidmonitor.service;

import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceAdditionalSourceTests {

    @Test
    void additionalSourceCandidateUsesSameClassifierAndReviewPipeline() {
        G2bApiService service = new EmptyG2bFixtureService(
                (startDate, endDate) -> List.of(linkedAgencyCandidate())
        );
        BidQualificationDto notice = query(service).getFirst();

        assertEquals("202608222-00", notice.getBidNtceNo());
        assertEquals("ADDITIONAL", notice.getSourceCode());
        assertEquals("202608222-00", notice.getSourceNoticeId());
        assertNull(notice.getRevision());
        assertEquals("QUALIFICATION_REVIEW", notice.getAwardMethodCategory());
        assertEquals("CONFIRMED", notice.getAwardMethodStatus());
        assertEquals("STRUCTURED_DETAIL", notice.getAwardMethodSource());
        assertEquals("추가확인필요", notice.getReviewStatus());
    }

    @Test
    void sameSourceAndNoticeIdAreDeduplicatedButDifferentSourcesDoNotCollide() {
        G2bApiService service = new EmptyG2bFixtureService(List.of(
                collector("D2B", List.of(
                        candidate("SHARED-00", "SOURCE-ID", null),
                        candidate("OTHER-NUMBER-00", "SOURCE-ID", null)
                )),
                collector("KOGAS", List.of(candidate("SHARED-00", "SOURCE-ID", null)))
        ));

        List<BidQualificationDto> result = query(service);

        assertEquals(2, result.size());
        assertEquals(List.of("D2B", "KOGAS"),
                result.stream().map(BidQualificationDto::getSourceCode).toList());
    }

    @Test
    void explicitRevisionsRemainDistinctAndMissingRevisionIsNotInvented() {
        G2bApiService service = new EmptyG2bFixtureService(collector("D2B", List.of(
                candidate("NOTICE-00", "NOTICE", "1"),
                candidate("NOTICE-01", "NOTICE", "2"),
                candidate("NO-REVISION-00", "NO-REVISION", null)
        )));

        List<BidQualificationDto> result = query(service);

        assertEquals(3, result.size());
        assertNull(result.get(2).getRevision());
    }

    @Test
    void missingSourceNoticeIdFallsBackToExistingBidNoticeNumber() {
        G2bApiService service = new EmptyG2bFixtureService(collector("LEGACY_SOURCE", List.of(
                candidate("LEGACY-00", null, null), candidate("LEGACY-00", null, null)
        )));

        List<BidQualificationDto> result = query(service);

        assertEquals(1, result.size());
        assertEquals("LEGACY-00", result.getFirst().getSourceNoticeId());
    }

    @Test
    void failedCollectorDoesNotDiscardAnotherCollectorsResults() {
        G2bApiService service = new EmptyG2bFixtureService(List.of(
                failingCollector("D2B", "https://internal.example/secret?token=hidden"),
                collector("KOGAS", List.of(candidate("KOGAS-00", "KOGAS-ID", null)))
        ));

        List<BidQualificationDto> result = query(service);

        assertEquals(1, result.size());
        assertEquals("KOGAS", result.getFirst().getSourceCode());
    }

    @Test
    void allAdditionalCollectorsFailWithSafeNonEmptyFailure() {
        G2bApiService service = new EmptyG2bFixtureService(List.of(
                failingCollector("D2B", "secret-url"),
                failingCollector("KOGAS", "credential-value")
        ));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> query(service));

        assertTrue(exception.getMessage().contains("D2B"));
        assertTrue(exception.getMessage().contains("KOGAS"));
        assertFalse(exception.getMessage().contains("secret-url"));
        assertFalse(exception.getMessage().contains("credential-value"));
    }

    @Test
    void responseKeepsLegacyFieldsAndAddsSourceFields() throws Exception {
        BidQualificationDto candidate = candidate("NOTICE-00", "SOURCE-ID", "3");
        candidate.setBidNtceDtlUrl("https://public.example/notices/1");
        BidQualificationDto result = query(new EmptyG2bFixtureService(
                collector("D2B", List.of(candidate))
        )).getFirst();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertEquals("NOTICE-00", json.path("bidNtceNo").asText());
        assertEquals("https://public.example/notices/1", json.path("bidNtceDtlUrl").asText());
        assertEquals("D2B", json.path("sourceCode").asText());
        assertEquals("SOURCE-ID", json.path("sourceNoticeId").asText());
        assertEquals("3", json.path("revision").asText());
        assertEquals(json.path("bidNtceDtlUrl").asText(), json.path("detailUrl").asText());
    }

    private static List<BidQualificationDto> query(G2bApiService service) {
        return service.getTargetBidQualificationList(
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27), Set.of("6146", "1468")
        );
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

    private static BidQualificationDto candidate(String bidNtceNo, String sourceNoticeId, String revision) {
        BidQualificationDto candidate = linkedAgencyCandidate();
        candidate.setBidNtceNo(bidNtceNo);
        candidate.setSourceNoticeId(sourceNoticeId);
        candidate.setRevision(revision);
        return candidate;
    }

    private static BidCandidateCollector collector(String sourceCode, List<BidQualificationDto> candidates) {
        return new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                return candidates;
            }
        };
    }

    private static BidCandidateCollector failingCollector(String sourceCode, String sensitiveMessage) {
        return new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                throw new IllegalStateException(sensitiveMessage);
            }
        };
    }

    private static final class EmptyG2bFixtureService extends G2bApiService {
        private EmptyG2bFixtureService(BidCandidateCollector collector) {
            super(new BidAwardMethodClassifier(), List.of(collector));
        }

        private EmptyG2bFixtureService(List<BidCandidateCollector> collectors) {
            super(new BidAwardMethodClassifier(), collectors);
        }

        @Override
        public List<BidDto> getBidDtoList(LocalDate startDate, LocalDate endDate) {
            return List.of();
        }
    }
}
