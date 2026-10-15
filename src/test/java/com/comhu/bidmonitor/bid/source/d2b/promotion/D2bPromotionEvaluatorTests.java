package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.persistence.AllowedIndustryCodeRepository;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.classifier.BidRelevance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.comhu.bidmonitor.bid.source.d2b.promotion.D2bPromotionDecision.*;
import static com.comhu.bidmonitor.bid.source.d2b.promotion.D2bQualificationEvidence.*;
import static org.junit.jupiter.api.Assertions.*;

class D2bPromotionEvaluatorTests {
    private final MemoryCodes repository = new MemoryCodes();
    private final AllowedIndustryCodeProvider provider = new AllowedIndustryCodeProvider(repository);
    private final D2bPromotionEvaluator evaluator = new D2bPromotionEvaluator(provider);

    @ParameterizedTest
    @CsvSource({
            "MATCH,RELEVANT,MAIN", "MATCH,UNKNOWN,MAIN", "MATCH,IRRELEVANT,MAIN",
            "MISMATCH,RELEVANT,ARCHIVED", "MISMATCH,UNKNOWN,ARCHIVED", "MISMATCH,IRRELEVANT,ARCHIVED",
            "INCOMPLETE,RELEVANT,MAIN", "INCOMPLETE,UNKNOWN,REVIEW", "INCOMPLETE,IRRELEVANT,REVIEW",
            "ABSENT,RELEVANT,MAIN", "ABSENT,UNKNOWN,REVIEW", "ABSENT,IRRELEVANT,REVIEW"
    })
    void fullDecisionTable(QualificationState qualification, BidRelevance relevance, PromotionState promotion) {
        D2bQualificationEvidence evidence = switch (qualification) {
            case MATCH -> verified("업종 6525", List.of(List.of("6525")));
            case MISMATCH -> verified("업종 9999", List.of(List.of("9999")));
            case INCOMPLETE -> rawDetail("업종 조건은 첨부 참조", "", "");
            case ABSENT -> rawDetail("", "", "");
        };
        D2bPromotionDecision result = evaluator.evaluate(evidence, relevance);
        assertEquals(qualification, result.qualification().orElseThrow());
        assertEquals(promotion, result.promotion().orElseThrow());
        assertFalse(result.retryRequired());
        assertEquals(Set.of("6525"), result.allowedCodes());
    }

    @Test
    void verifiedOrAlternativesAndRequirementsUseConfiguredCodes() {
        // Test evidence explicitly certifies the full expression; this is not a text parser fixture.
        D2bQualificationEvidence evidence = verified("(6525 AND 9999) OR (1234 AND 5678)",
                List.of(List.of("6525", "9999"), List.of("1234", "5678")));
        assertEquals(QualificationState.MISMATCH,
                evaluator.evaluate(evidence, BidRelevance.RELEVANT).qualification().orElseThrow());

        provider.replaceCodes(Set.of("1234", "5678"));
        D2bPromotionDecision matched = evaluator.evaluate(evidence, BidRelevance.IRRELEVANT);
        assertEquals(QualificationState.MATCH, matched.qualification().orElseThrow());
        assertEquals(PromotionState.MAIN, matched.promotion().orElseThrow());

        provider.replaceCodes(Set.of("1234"));
        assertEquals(QualificationState.MISMATCH,
                evaluator.evaluate(evidence, BidRelevance.RELEVANT).qualification().orElseThrow());
        assertEquals(Set.of("1234", "5678"), matched.allowedCodes());
    }

    @Test
    void omittedOrAlternativeCannotProveMismatch() {
        D2bQualificationEvidence evidence = new D2bQualificationEvidence(DetailStatus.AVAILABLE,
                "9999 또는 6525", "", "", ParseCompleteness.PARTIAL, List.of(List.of("9999")));
        D2bPromotionDecision result = evaluator.evaluate(evidence, BidRelevance.UNKNOWN);
        assertEquals(QualificationState.INCOMPLETE, result.qualification().orElseThrow());
        assertEquals(Reason.PARSE_PARTIAL, result.reason());
    }

    @Test
    void partialMatchDoesNotHideMissingAndRequirement() {
        D2bQualificationEvidence evidence = new D2bQualificationEvidence(DetailStatus.AVAILABLE,
                "6525 및 첨부의 추가 자격", "", "추가 조건 미해석", ParseCompleteness.PARTIAL,
                List.of(List.of("6525")));
        assertEquals(QualificationState.INCOMPLETE,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).qualification().orElseThrow());
    }

    @Test
    void flattenedCurrentParserGroupsDoNotCertifySourceRelationship() {
        D2bQualificationEvidence evidence = new D2bQualificationEvidence(DetailStatus.AVAILABLE,
                "6525 또는 9999", "업종 1234", "별도 조건과 관계 미확인",
                ParseCompleteness.UNVERIFIED, List.of(List.of("6525", "9999")));
        assertEquals(Reason.RELATIONSHIP_UNVERIFIED,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).reason());
    }

    @Test
    void explicitLookingRawExpressionStillNeedsVerifiedRelationships() {
        D2bQualificationEvidence evidence = rawDetail("(6525 AND 9999) OR 1234", "", "");
        assertEquals(QualificationState.INCOMPLETE,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).qualification().orElseThrow());
    }

    @Test
    void partialEmptyExtractionIsNotAbsence() {
        D2bQualificationEvidence evidence = new D2bQualificationEvidence(DetailStatus.AVAILABLE,
                "", "", "", ParseCompleteness.PARTIAL, List.of());
        assertEquals(Reason.PARSE_PARTIAL,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).reason());
    }

    @Test
    void groupsWithoutSupportingRawEvidenceCannotCertifyMatch() {
        D2bQualificationEvidence evidence = verified("", List.of(List.of("6525")));
        assertEquals(Reason.INVALID_GROUPS,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).reason());
    }

    @ParameterizedTest
    @CsvSource({"6525,,", ",6525,", ",,첨부의 자격조건"})
    void eitherFieldOrOriginalConditionsPreventsAbsence(String license, String industry, String original) {
        assertEquals(QualificationState.INCOMPLETE, evaluator.evaluate(
                rawDetail(license == null ? "" : license, industry == null ? "" : industry,
                        original == null ? "" : original), BidRelevance.UNKNOWN
        ).qualification().orElseThrow());
    }

    @Test
    void unavailableSourceFieldIsNotAnEmptyQualification() {
        for (D2bQualificationEvidence evidence : List.of(
                rawDetail(null, "", ""), rawDetail("", null, ""), rawDetail("", "", null))) {
            assertEquals(Reason.SOURCE_FIELDS_MISSING,
                    evaluator.evaluate(evidence, BidRelevance.UNKNOWN).reason());
        }
    }

    @ParameterizedTest
    @EnumSource(value = DetailStatus.class, names = {"FAILED", "ITEM_MISSING"})
    void missingItemAndFailedDetailRequireRetryNotAbsence(DetailStatus status) {
        D2bQualificationEvidence evidence = new D2bQualificationEvidence(status, "", "", "",
                ParseCompleteness.UNVERIFIED, List.of());
        for (BidRelevance relevance : BidRelevance.values()) {
            D2bPromotionDecision result = evaluator.evaluate(evidence, relevance);
            assertTrue(result.retryRequired());
            assertTrue(result.qualification().isEmpty());
            assertTrue(result.promotion().isEmpty());
            assertEquals(status == DetailStatus.FAILED ? Reason.DETAIL_FAILED : Reason.DETAIL_ITEM_MISSING,
                    result.reason());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidGroups")
    void invalidOrEmptyGroupsCannotProveMismatch(List<List<String>> groups) {
        D2bPromotionDecision result = evaluator.evaluate(verified("원문 자격 있음", groups), BidRelevance.UNKNOWN);
        assertEquals(QualificationState.INCOMPLETE, result.qualification().orElseThrow());
        assertEquals(Reason.INVALID_GROUPS, result.reason());
    }

    static Stream<List<List<String>>> invalidGroups() {
        return Stream.of(List.of(), List.of(List.of()), List.of(List.of("")),
                List.of(List.of("6525", "미확인")), List.of(Arrays.asList((String) null)),
                Arrays.asList((List<String>) null), List.of(List.of("6525"), List.of()));
    }

    @Test
    void evidenceSnapshotsCannotBeChangedAfterConstruction() {
        List<String> group = new ArrayList<>(List.of("6525"));
        List<List<String>> groups = new ArrayList<>(List.of(group));
        D2bQualificationEvidence evidence = verified("6525", groups);
        group.add("9999");
        groups.clear();
        assertEquals(QualificationState.MATCH,
                evaluator.evaluate(evidence, BidRelevance.UNKNOWN).qualification().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> evidence.licenseGroups().getFirst().add("9999"));
    }

    @Test
    void emptyAllowedConfigurationDoesNotBecomeMismatch() {
        repository.codes = Set.of();
        assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(verified("6525", List.of(List.of("6525"))), BidRelevance.UNKNOWN));
    }

    private static D2bQualificationEvidence verified(String raw, List<List<String>> groups) {
        return new D2bQualificationEvidence(DetailStatus.AVAILABLE, raw, "", "",
                ParseCompleteness.VERIFIED_COMPLETE, groups);
    }

    private static final class MemoryCodes implements AllowedIndustryCodeRepository {
        private Set<String> codes = Set.of("6525");

        @Override
        public Set<String> findAll() { return codes; }

        @Override
        public Set<String> replaceAll(Set<String> codes) {
            this.codes = Set.copyOf(codes);
            return this.codes;
        }
    }
}
