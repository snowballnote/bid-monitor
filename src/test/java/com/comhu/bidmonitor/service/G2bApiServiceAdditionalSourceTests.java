package com.comhu.bidmonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comhu.bidmonitor.bid.source.BidCandidateCollector;
import com.comhu.bidmonitor.bid.source.d2b.D2bDailyQuotaExceededException;
import com.comhu.bidmonitor.classifier.BidAwardMethodClassifier;
import com.comhu.bidmonitor.dto.BidDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G2bApiServiceAdditionalSourceTests {

    @Test
    void separatedG2bExecutionDoesNotInvokeAdditionalCollectors() {
        AtomicBoolean additionalInvoked = new AtomicBoolean(false);
        BidCandidateCollector additional = new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return "KOREA_EXPRESSWAY";
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                additionalInvoked.set(true);
                throw new IllegalStateException("must not run");
            }
        };
        G2bApiService service = new G2bOnlyFixtureService(additional);

        List<BidQualificationDto> result = service.getG2bTargetBidQualificationList(
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 27), Set.of("6146", "1468")
        );

        assertEquals(1, result.size());
        assertEquals("G2B", result.getFirst().getSourceCode());
        assertEquals("G2B-ONLY-00", result.getFirst().getSourceNoticeId());
        assertFalse(additionalInvoked.get());
    }

    @Test
    void disabledD2bIsNotInvokedAndG2bAndExpresswayResultsRemain() {
        AtomicBoolean d2bInvoked = new AtomicBoolean(false);
        BidCandidateCollector disabledD2b = new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return "D2B";
            }

            @Override
            public boolean executionEnabled() {
                return false;
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                d2bInvoked.set(true);
                throw new IllegalStateException("D2B must remain disabled");
            }
        };
        G2bApiService service = new G2bOnlyFixtureService(List.of(
                disabledD2b,
                collector("KOREA_EXPRESSWAY", List.of(candidate("EX-00", "EX-ID", null)))
        ));

        List<BidQualificationDto> result = query(service);

        assertFalse(d2bInvoked.get());
        assertEquals(List.of("G2B", "KOREA_EXPRESSWAY"),
                result.stream().map(BidQualificationDto::getSourceCode).toList());
    }

    @Test
    void exhaustedD2bQuotaDoesNotDiscardG2bOrExpresswayResults() {
        G2bApiService service = new G2bOnlyFixtureService(List.of(
                failingCollector("D2B", new D2bDailyQuotaExceededException(LocalDate.of(2026, 8, 27))),
                collector("KOREA_EXPRESSWAY", List.of(candidate("EX-00", "EX-ID", null)))
        ));

        List<BidQualificationDto> result = query(service);

        assertEquals(List.of("G2B", "KOREA_EXPRESSWAY"),
                result.stream().map(BidQualificationDto::getSourceCode).toList());
    }

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
    void expresswayAndKogasUseTheSameCommonQualificationEvaluation() {
        G2bApiService service = new EmptyG2bFixtureService(List.of());
        BidQualificationDto expressway = eligibleCandidate("EX-ELIGIBLE");
        BidQualificationDto kogas = eligibleCandidate("KOGAS-ELIGIBLE");

        BidQualificationDto expresswayResult = service.processAdditionalBidQualificationList(
                collector("KOREA_EXPRESSWAY", List.of()), List.of(expressway), Set.of("6146", "1468")
        ).getFirst();
        BidQualificationDto kogasResult = service.processAdditionalBidQualificationList(
                collector("KOGAS", List.of()), List.of(kogas), Set.of("6146", "1468")
        ).getFirst();

        assertEquals("KOREA_EXPRESSWAY", expresswayResult.getSourceCode());
        assertEquals("KOGAS", kogasResult.getSourceCode());
        assertEquals("검토대상", expresswayResult.getReviewStatus());
        assertEquals(expresswayResult.getReviewStatus(), kogasResult.getReviewStatus());
        assertEquals(expresswayResult.getReviewReason(), kogasResult.getReviewReason());
        assertEquals(expresswayResult.getAwardMethodCategory(), kogasResult.getAwardMethodCategory());
    }

    @Test
    void explicitMatchingQualificationOverridesIrrelevantTitleForEveryAdditionalSource() {
        G2bApiService service = new EmptyG2bFixtureService(List.of());

        for (String sourceCode : List.of("D2B", "LH", "KOGAS", "KOREA_EXPRESSWAY")) {
            BidQualificationDto candidate = qualifiedCandidate(
                    sourceCode + "-6525", "건설폐기물 처리 용역", group("1", "6525")
            );

            List<BidQualificationDto> result = service.processAdditionalBidQualificationList(
                    collector(sourceCode, List.of()), List.of(candidate), Set.of("6525")
            );

            assertEquals(1, result.size(), sourceCode);
            assertEquals("검토대상", result.getFirst().getReviewStatus(), sourceCode);
        }

        BidQualificationDto arbitrary = qualifiedCandidate(
                "KOGAS-9999", "건축 감리 용역", group("1", "9999")
        );
        assertEquals(1, service.processAdditionalBidQualificationList(
                collector("KOGAS", List.of()), List.of(arbitrary), Set.of("9999")
        ).size());
    }

    @Test
    void explicitMismatchOverridesRelevantTitleAndPreservesQualificationResult() {
        G2bApiService service = new EmptyG2bFixtureService(List.of());
        BidQualificationDto candidate = qualifiedCandidate(
                "D2B-MISMATCH", "정보시스템 감리 용역", group("1", "6146")
        );

        BidQualificationDto result = service.processAdditionalBidQualificationList(
                collector("D2B", List.of()), List.of(candidate), Set.of("6525")
        ).getFirst();

        assertEquals("추가확인필요", result.getReviewStatus());
        assertTrue(result.getReviewReason().contains("6146"));
    }

    @Test
    void titleClassifierIsUsedOnlyWhenQualificationIsInsufficient() {
        G2bApiService service = new EmptyG2bFixtureService(List.of());
        BidQualificationDto irrelevant = qualifiedCandidate(
                "NO-LICENSE-IRRELEVANT", "건설폐기물 처리 용역"
        );
        BidQualificationDto unknown = qualifiedCandidate("NO-LICENSE-UNKNOWN", "감리 용역");
        BidQualificationDto relevant = qualifiedCandidate(
                "NO-LICENSE-RELEVANT", "정보시스템 감리 용역"
        );

        assertTrue(service.processAdditionalBidQualificationList(
                collector("LH", List.of()), List.of(irrelevant), Set.of("6525")
        ).isEmpty());
        assertEquals(1, service.processAdditionalBidQualificationList(
                collector("LH", List.of()), List.of(unknown), Set.of("6525")
        ).size());
        assertEquals(1, service.processAdditionalBidQualificationList(
                collector("LH", List.of()), List.of(relevant), Set.of("6525")
        ).size());
    }

    @Test
    void incompleteQualificationUsesTitleAsSecondarySignal() {
        G2bApiService service = new EmptyG2bFixtureService(List.of());
        BidQualificationDto candidate = qualifiedCandidate(
                "LH-INCOMPLETE", "지하안전평가 용역",
                new LicenseRequirementGroup("1", List.of(
                        new LicenseRequirement("1", "", "확인되지 않은 면허", "확인되지 않은 면허")
                ))
        );

        assertTrue(service.processAdditionalBidQualificationList(
                collector("LH", List.of()), List.of(candidate), Set.of("6525")
        ).isEmpty());
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
    void failedCollectorLogsMessageAndDebugStack() {
        Logger logger = (Logger) LoggerFactory.getLogger(G2bApiService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            G2bApiService service = new EmptyG2bFixtureService(List.of(
                    failingCollector("KOGAS", new IllegalStateException(
                            "KOGAS 목록 표를 찾을 수 없습니다.",
                            new IllegalStateException("responseBody=https://private.example token=hidden")
                    )),
                    collector("D2B", List.of(candidate("D2B-00", "D2B-ID", null)))
            ));

            query(service);

            ILoggingEvent warning = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .findFirst()
                    .orElseThrow();
            ILoggingEvent debug = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .findFirst()
                    .orElseThrow();
            assertTrue(warning.getFormattedMessage().contains("errorType=IllegalStateException"));
            assertTrue(warning.getFormattedMessage().contains("errorMessage=KOGAS 목록 표를 찾을 수 없습니다."));
            assertNull(warning.getThrowableProxy());
            assertTrue(debug.getFormattedMessage().contains("sourceCode=KOGAS"));
            assertTrue(debug.getFormattedMessage().contains(IllegalStateException.class.getName()));
            assertTrue(debug.getFormattedMessage().contains("G2bApiServiceAdditionalSourceTests"));
            assertNull(debug.getThrowableProxy());
            assertFalse(warning.getFormattedMessage().contains("private.example"));
            assertFalse(debug.getFormattedMessage().contains("private.example"));
            assertFalse(debug.getFormattedMessage().contains("hidden"));
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
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

    private static BidQualificationDto eligibleCandidate(String noticeId) {
        BidQualificationDto candidate = candidate(noticeId, noticeId, null);
        candidate.setSucsfbidMthdNm("소액수의견적");
        candidate.setSucsfbidMthdCd("낙030029");
        candidate.setParticipationRegion("제한없음");
        candidate.setArsltCmptYn("N");
        candidate.setPqEvalYn("N");
        candidate.setTpEvalYn("N");
        candidate.setLicenseGroups(List.of(new com.comhu.bidmonitor.dto.LicenseRequirementGroup("1", List.of(
                new com.comhu.bidmonitor.dto.LicenseRequirement(
                        "1", "6146", "정보시스템 감리법인", "정보시스템 감리법인/6146"
                )
        ))));
        return candidate;
    }

    private static BidQualificationDto qualifiedCandidate(
            String noticeId,
            String title,
            LicenseRequirementGroup... groups
    ) {
        BidQualificationDto candidate = eligibleCandidate(noticeId);
        candidate.setBidNtceNm(title);
        candidate.setLicenseGroups(List.of(groups));
        return candidate;
    }

    private static LicenseRequirementGroup group(String groupNo, String... codes) {
        List<LicenseRequirement> requirements = java.util.stream.IntStream.range(0, codes.length)
                .mapToObj(index -> new LicenseRequirement(
                        Integer.toString(index + 1), codes[index], "면허 " + codes[index], "면허/" + codes[index]
                ))
                .toList();
        return new LicenseRequirementGroup(groupNo, requirements);
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
        return failingCollector(sourceCode, new IllegalStateException(sensitiveMessage));
    }

    private static BidCandidateCollector failingCollector(String sourceCode, RuntimeException failure) {
        return new BidCandidateCollector() {
            @Override
            public String sourceCode() {
                return sourceCode;
            }

            @Override
            public List<BidQualificationDto> collect(LocalDate startDate, LocalDate endDate) {
                throw failure;
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
        public List<BidDto> getBidDtoList(
                LocalDate startDate,
                LocalDate endDate,
                Set<String> allowedIndustryCodes
        ) {
            return List.of();
        }
    }

    private static final class G2bOnlyFixtureService extends G2bApiService {
        private G2bOnlyFixtureService(BidCandidateCollector additionalCollector) {
            super(new BidAwardMethodClassifier(), List.of(additionalCollector));
        }

        private G2bOnlyFixtureService(List<BidCandidateCollector> additionalCollectors) {
            super(new BidAwardMethodClassifier(), additionalCollectors);
        }

        @Override
        public List<BidDto> getBidDtoList(
                LocalDate startDate,
                LocalDate endDate,
                Set<String> allowedIndustryCodes
        ) {
            BidDto bid = new BidDto();
            bid.setBidNtceNo("G2B-ONLY-00");
            return List.of(bid);
        }

        @Override
        public BidQualificationDto getBidQualification(String bidNtceNo, Set<String> allowedLicenseCodes) {
            BidQualificationDto candidate = candidate(bidNtceNo, bidNtceNo, null);
            candidate.setSourceCode("G2B");
            return candidate;
        }
    }
}
