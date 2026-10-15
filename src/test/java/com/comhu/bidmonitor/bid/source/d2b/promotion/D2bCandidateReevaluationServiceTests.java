package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.persistence.BidSourceCandidate;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidateRepository;
import com.comhu.bidmonitor.bid.persistence.jdbc.JdbcAllowedIndustryCodeRepository;
import com.comhu.bidmonitor.bid.persistence.jdbc.JdbcBidSourceCandidateRepository;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.classifier.BidRelevanceClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class D2bCandidateReevaluationServiceTests {
    private static final Instant BEFORE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BEFORE.plusSeconds(100), ZoneOffset.UTC);
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcBidSourceCandidateRepository candidates;
    private AllowedIndustryCodeProvider provider;
    private D2bPromotionEvaluator evaluator;
    private DataSourceTransactionManager manager;
    private D2bCandidateReevaluationService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:reevaluate-" + UUID.randomUUID() + ";LOCK_TIMEOUT=10000");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        candidates = new JdbcBidSourceCandidateRepository(jdbc);
        provider = new AllowedIndustryCodeProvider(new JdbcAllowedIndustryCodeRepository(jdbc));
        provider.replaceCodes(Set.of("6525"));
        evaluator = new D2bPromotionEvaluator(provider);
        service = service(candidates);
    }

    @AfterEach
    void close() { dataSource.close(); }

    @ParameterizedTest
    @CsvSource({
            "MATCH,RELEVANT,MAIN", "MATCH,UNKNOWN,MAIN", "MATCH,IRRELEVANT,MAIN",
            "MISMATCH,RELEVANT,ARCHIVED", "MISMATCH,UNKNOWN,ARCHIVED", "MISMATCH,IRRELEVANT,ARCHIVED",
            "INCOMPLETE,RELEVANT,MAIN", "INCOMPLETE,UNKNOWN,REVIEW", "INCOMPLETE,IRRELEVANT,REVIEW",
            "ABSENT,RELEVANT,MAIN", "ABSENT,UNKNOWN,REVIEW", "ABSENT,IRRELEVANT,REVIEW"})
    void restoresTwelveDecisionsWithoutChangingRawEvidence(String state, String relevance, String promotion) throws Exception {
        BidSourceCandidate before = seed("ONE", state, relevance);
        service.replaceAllowedCodes(Set.of("6525"));
        var after = get("ONE");
        assertEquals(state, after.qualificationState());
        assertEquals(promotion, after.promotionState());
        assertEquals(before.detailFields(), after.detailFields());
        assertEquals(before.parsedQualification(), after.parsedQualification());
        assertEquals(before.listFields(), after.listFields());
        assertEquals(before.discoveryPaths(), after.discoveryPaths());
        assertEquals(before.firstSeenAt(), after.firstSeenAt());
    }

    @Test
    void codesChangeBothWaysAndSameCodesAreIdempotent() throws Exception {
        seed("ONE", "MATCH", "IRRELEVANT");
        service.replaceAllowedCodes(Set.of("1234"));
        assertEquals("MISMATCH", get("ONE").qualificationState());
        assertEquals("ARCHIVED", get("ONE").promotionState());
        service.replaceAllowedCodes(Set.of("6525"));
        assertEquals("MATCH", get("ONE").qualificationState());
        var before = get("ONE");
        service.replaceAllowedCodes(List.of("6525", " 6525 "));
        assertEquals(before, get("ONE"));
        assertEquals("[\"6525\"]", before.allowedCodes());
        assertEquals(D2bCandidateEvidenceService.hash(before.allowedCodes()), before.allowedCodesHash());
    }

    @Test
    void verifiedGroupsKeepOrBetweenAndWithinSemantics() throws Exception {
        seed("ONE", "MATCH", "IRRELEVANT");
        String raw = get("ONE").detailFields();
        jdbc.update("UPDATE bid_source_candidate SET parsed_qualification=?", verified(raw,
                List.of(List.of("6525", "1234"), List.of("9999"))));
        service.replaceAllowedCodes(Set.of("6525"));
        assertEquals("MISMATCH", get("ONE").qualificationState());
        service.replaceAllowedCodes(Set.of("6525", "1234"));
        assertEquals("MATCH", get("ONE").qualificationState());
        service.replaceAllowedCodes(Set.of("9999"));
        assertEquals("MATCH", get("ONE").qualificationState());
    }

    @Test
    void diagnosticCodesAndUnboundOrPartialGroupsNeverCertifyQualification() throws Exception {
        seed("ONE", "INCOMPLETE", "IRRELEVANT");
        jdbc.update("UPDATE bid_source_candidate SET parsed_qualification=?",
                verified(get("ONE").detailFields(), List.of(List.of("6525"))));
        service.replaceAllowedCodes(Set.of("6525"));
        assertEquals("INCOMPLETE", get("ONE").qualificationState());
        jdbc.update("UPDATE bid_source_candidate SET parser_version='d2b-verified-groups-v1', detail_fields='{}'");
        service.replaceAllowedCodes(Set.of("1234"));
        assertEquals("INCOMPLETE", get("ONE").qualificationState());
        jdbc.update("UPDATE bid_source_candidate SET parsed_qualification='{broken'");
        service.replaceAllowedCodes(Set.of("6525"));
        assertEquals("INCOMPLETE", get("ONE").qualificationState());
        jdbc.update("UPDATE bid_source_candidate SET parser_version='d2b-public-fields-v1-unverified', parsed_qualification='{\"completeness\":\"PARTIAL\"}'");
        service.replaceAllowedCodes(Set.of("1234"));
        assertEquals("INCOMPLETE", get("ONE").qualificationState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGACY", "PENDING", "FAILED", "ITEM_MISSING"})
    void missingEvidenceRemainsUndecidedAndLegacyOriginSurvives(String status) throws Exception {
        seed("ONE", "ABSENT", "RELEVANT");
        jdbc.update("""
                UPDATE bid_source_candidate SET detail_status=?, qualification_state=NULL, promotion_state=NULL,
                    parser_version=?, parsed_qualification=?, decision_reason='LEGACY_DETAIL_UNAVAILABLE'
                """, status.equals("LEGACY") ? "PENDING" : status, status.equals("LEGACY") ? "legacy-unverified-v1" : "d2b-public-fields-v1-unverified",
                "{\"origin\":\"" + status + "\",\"allowedCodesKnown\":false}");
        String original = get("ONE").parsedQualification();
        service.replaceAllowedCodes(Set.of("1234"));
        assertNull(get("ONE").qualificationState());
        assertNull(get("ONE").promotionState());
        assertEquals(original, get("ONE").parsedQualification());
        assertEquals("[\"1234\"]", get("ONE").allowedCodes());
    }

    @Test
    void failureAfterDecisionUpdateRollsBackSettingsAndEveryDecision() throws Exception {
        seed("ONE", "MATCH", "UNKNOWN");
        seed("TWO", "MISMATCH", "UNKNOWN");
        var before = candidates.findD2bAfterId(0, 10);
        AtomicInteger updates = new AtomicInteger();
        var failing = new JdbcBidSourceCandidateRepository(jdbc) {
            @Override public void updateDecision(long id, BidSourceCandidate c) {
                super.updateDecision(id, c);
                if (updates.incrementAndGet() == 2) throw new IllegalStateException("fixture failure");
            }
        };
        assertThrows(IllegalStateException.class, () -> service(failing).replaceAllowedCodes(Set.of("1234")));
        assertEquals(Set.of("6525"), provider.currentCodes());
        assertEquals(before, candidates.findD2bAfterId(0, 10));
        service.replaceAllowedCodes(Set.of("1234"));
        assertEquals("MISMATCH", get("ONE").qualificationState());
    }

    @Test
    void pagesThroughAllD2bCandidatesAndNeverUpdatesOtherSources() throws Exception {
        for (int i = 0; i < 205; i++) seed("N" + i, "ABSENT", "UNKNOWN");
        for (String source : List.of("G2B", "LH", "KOGAS", "KWATER")) {
            seed(source, "ABSENT", "UNKNOWN");
            jdbc.update("UPDATE bid_source_candidate SET source_code=? WHERE source_notice_id=?", source, source);
        }
        var others = jdbc.queryForList("SELECT id,allowed_codes,policy_version,updated_at FROM bid_source_candidate WHERE source_code <> 'D2B' ORDER BY id");
        service.replaceAllowedCodes(Set.of("1234"));
        assertEquals(205, jdbc.queryForObject("SELECT COUNT(*) FROM bid_source_candidate WHERE source_code='D2B' AND allowed_codes='[\"1234\"]'", Integer.class));
        assertEquals(others, jdbc.queryForList("SELECT id,allowed_codes,policy_version,updated_at FROM bid_source_candidate WHERE source_code <> 'D2B' ORDER BY id"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void collectionAndSettingsSerializeInBothOrders(boolean collectionFirst) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        var firstRepository = new JdbcBidSourceCandidateRepository(jdbc) {
            @Override public void lockD2bEvaluation() {
                super.lockD2bEvaluation(); locked.countDown(); await(release);
            }
        };
        var secondRepository = new JdbcBidSourceCandidateRepository(jdbc) {
            @Override public void lockD2bEvaluation() { waiting.countDown(); super.lockD2bEvaluation(); }
        };
        Runnable first = collectionFirst ? () -> evidence(firstRepository).saveAll(List.of(observation()))
                : () -> service(firstRepository).replaceAllowedCodes(Set.of("1234"));
        Runnable second = collectionFirst ? () -> service(secondRepository).replaceAllowedCodes(Set.of("1234"))
                : () -> evidence(secondRepository).saveAll(List.of(observation()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(first);
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                Future<?> b = executor.submit(second);
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> b.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertEquals(Set.of("1234"), provider.currentCodes());
        assertEquals("[\"1234\"]", get("LIVE").allowedCodes());
        assertEquals("INCOMPLETE", get("LIVE").qualificationState());
    }

    private D2bCandidateReevaluationService service(BidSourceCandidateRepository r) {
        return new D2bCandidateReevaluationService(r, provider, evaluator, manager, CLOCK);
    }
    private D2bCandidateEvidenceService evidence(BidSourceCandidateRepository r) {
        return new D2bCandidateEvidenceService(r, evaluator, new BidRelevanceClassifier(), manager, CLOCK);
    }
    private D2bCandidateObservation observation() {
        return new D2bCandidateObservation("LIVE", "1", Set.of("DOMESTIC_COMPETITIVE"), Set.of("COVERAGE"),
                "냉장고 구매", Map.of(), Map.of("lcnsLmttList", "6525"), "AVAILABLE");
    }
    private BidSourceCandidate get(String id) { return candidates.findByIdentity("D2B", id, "1").orElseThrow(); }
    private BidSourceCandidate seed(String id, String state, String relevance) throws Exception {
        boolean verified = state.equals("MATCH") || state.equals("MISMATCH");
        String code = state.equals("MISMATCH") ? "9999" : "6525";
        String raw = state.equals("ABSENT") ? "{}" : json.writeValueAsString(Map.of("lcnsLmttList", code, "industClList", ""));
        String parsed = verified ? verified(raw, List.of(List.of(code))) : "{\"completeness\":\"UNVERIFIED\"}";
        var c = new BidSourceCandidate("D2B", id, "1", "[]", "[\"COVERAGE\"]", "fixture title", "{}", raw,
                parsed, "AVAILABLE", "INCOMPLETE", relevance, "REVIEW", "RELATIONSHIP_UNVERIFIED", "[\"6525\"]",
                "", verified ? "d2b-verified-groups-v1" : "d2b-public-fields-v1-unverified", "old-policy", BEFORE, BEFORE);
        candidates.insert(c);
        return c;
    }
    private String verified(String raw, List<List<String>> groups) throws Exception {
        return json.writeValueAsString(Map.of("completeness", "VERIFIED_COMPLETE", "allConditionsVerified", true,
                "rawEvidenceHash", D2bCandidateEvidenceService.hash(raw), "originalConditions", "verified fixture clauses",
                "licenseGroups", groups));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test release"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
