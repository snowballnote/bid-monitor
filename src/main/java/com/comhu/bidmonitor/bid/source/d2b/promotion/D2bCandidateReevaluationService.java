package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.persistence.BidSourceCandidate;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidateRepository;
import com.comhu.bidmonitor.bid.settings.AllowedIndustryCodeProvider;
import com.comhu.bidmonitor.classifier.BidRelevance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.comhu.bidmonitor.bid.source.d2b.promotion.D2bQualificationEvidence.*;

@Service
public class D2bCandidateReevaluationService {
    public static final String POLICY_VERSION = "d2b-promotion-v2";
    private static final int PAGE_SIZE = 200;
    private final BidSourceCandidateRepository candidates;
    private final AllowedIndustryCodeProvider provider;
    private final D2bPromotionEvaluator evaluator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public D2bCandidateReevaluationService(BidSourceCandidateRepository candidates,
            AllowedIndustryCodeProvider provider, D2bPromotionEvaluator evaluator,
            PlatformTransactionManager manager, Clock clock) {
        this.candidates = candidates;
        this.provider = provider;
        this.evaluator = evaluator;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    /** Settings and every decision commit together; evidence writers take the same DB lock. */
    public Set<String> replaceAllowedCodes(Collection<String> requestedCodes) {
        return transaction.execute(status -> {
            candidates.lockD2bEvaluation();
            Set<String> codes = provider.replaceCodes(requestedCodes);
            String snapshot;
            try { snapshot = json.writeValueAsString(new TreeSet<>(codes)); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Code snapshot serialization failed."); }
            String hash = D2bCandidateEvidenceService.hash(snapshot);
            long afterId = 0;
            while (true) {
                var page = candidates.findD2bAfterId(afterId, PAGE_SIZE);
                if (page.isEmpty()) break;
                for (var row : page) {
                    BidSourceCandidate updated = reevaluate(row.candidate(), codes, snapshot, hash);
                    if (updated != null) candidates.updateDecision(row.id(), updated);
                }
                afterId = page.getLast().id();
            }
            return codes;
        });
    }

    private BidSourceCandidate reevaluate(BidSourceCandidate old, Set<String> codes, String snapshot, String hash) {
        var decision = evaluator.evaluate(restore(old), BidRelevance.valueOf(old.titleRelevance()), codes);
        String reason = "PENDING".equals(old.detailStatus()) ? old.decisionReason() : decision.reason().name();
        String qualification = decision.qualification().map(Enum::name).orElse(null);
        String promotion = decision.promotion().map(Enum::name).orElse(null);
        if (Objects.equals(qualification, old.qualificationState()) && Objects.equals(promotion, old.promotionState())
                && reason.equals(old.decisionReason()) && snapshot.equals(old.allowedCodes())
                && hash.equals(old.allowedCodesHash()) && POLICY_VERSION.equals(old.policyVersion())) return null;
        return new BidSourceCandidate(old.sourceCode(), old.sourceNoticeId(), old.revisionKey(),
                old.discoveryOperations(), old.discoveryPaths(), old.title(), old.listFields(), old.detailFields(),
                old.parsedQualification(), old.detailStatus(), qualification, old.titleRelevance(), promotion,
                reason, snapshot, hash, old.parserVersion(), POLICY_VERSION, old.firstSeenAt(), clock.instant());
    }

    private D2bQualificationEvidence restore(BidSourceCandidate c) {
        if (!"AVAILABLE".equals(c.detailStatus())) {
            return new D2bQualificationEvidence("ITEM_MISSING".equals(c.detailStatus())
                    ? DetailStatus.ITEM_MISSING : DetailStatus.FAILED,
                    null, null, null, ParseCompleteness.UNVERIFIED, List.of());
        }
        try {
            JsonNode raw = json.readTree(c.detailFields());
            JsonNode parsed = json.readTree(c.parsedQualification());
            if (raw == null || !raw.isObject() || parsed == null || !parsed.isObject()) return incomplete();
            if ("d2b-public-fields-v1-unverified".equals(c.parserVersion())) {
                if (!"UNVERIFIED".equals(parsed.path("completeness").asText())) return incomplete();
                // Empty fields in a successful Phase 2 detail item are genuine absence.
                // Diagnostic code arrays never establish OR/AND completeness.
                return D2bQualificationEvidence.rawDetail(text(raw, "lcnsLmttList", ""),
                        text(raw, "industClList", ""), "");
            }
            // Reserved trusted-producer contract. No current live parser emits this version.
            // Certification must cover all clauses and be bound to the exact saved raw detail.
            if (!"d2b-verified-groups-v1".equals(c.parserVersion())
                    || !"VERIFIED_COMPLETE".equals(parsed.path("completeness").asText())
                    || !parsed.path("allConditionsVerified").asBoolean(false)
                    || !D2bCandidateEvidenceService.hash(c.detailFields()).equals(parsed.path("rawEvidenceHash").asText())
                    || !parsed.path("licenseGroups").isArray()) return incomplete();
            List<List<String>> groups = new ArrayList<>();
            for (JsonNode group : parsed.path("licenseGroups")) {
                if (!group.isArray()) return incomplete();
                List<String> codes = new ArrayList<>();
                for (JsonNode code : group) {
                    if (!code.isTextual()) return incomplete();
                    codes.add(code.textValue());
                }
                groups.add(codes);
            }
            return new D2bQualificationEvidence(DetailStatus.AVAILABLE, text(raw, "lcnsLmttList", null),
                    text(raw, "industClList", null), text(parsed, "originalConditions", null),
                    ParseCompleteness.VERIFIED_COMPLETE, groups);
        } catch (JsonProcessingException e) {
            return incomplete();
        }
    }

    private String text(JsonNode node, String field, String missing) {
        JsonNode value = node.get(field);
        return value == null ? missing : value.isTextual() ? value.textValue() : null;
    }

    private D2bQualificationEvidence incomplete() {
        return D2bQualificationEvidence.rawDetail(null, null, null);
    }
}
