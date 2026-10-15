package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.license.LicenseNormalizer;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidate;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidateRepository;
import com.comhu.bidmonitor.classifier.BidRelevanceClassifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class D2bCandidateEvidenceService {
    public static final Set<String> PUBLIC_FIELDS = Set.of(
            "demandYear", "pblancYear", "pblancNo", "pblancOdr", "dcsNo", "iemNo", "cntrwkNo", "orntCode", "ornt",
            "bidNm", "othbcNtatNm", "cntrwkNm", "pblancDate", "ntatPlanDate",
            "biddocPresentnClosDt", "prqudoPresentnClosDt", "opengDt", "budgetAmount",
            "bsicExpt", "baseAmnt", "cntrctMth", "bidStle", "ntatStle", "pblancSe",
            "progrsSttus", "pblancSeCode", "sucbidrDecsnMth", "lcnsLmttList", "industClList", "areaLmttList"
    );
    private final BidSourceCandidateRepository repository;
    private final D2bPromotionEvaluator evaluator;
    private final BidRelevanceClassifier classifier;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public D2bCandidateEvidenceService(BidSourceCandidateRepository repository, D2bPromotionEvaluator evaluator,
            BidRelevanceClassifier classifier, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.classifier = classifier;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void saveAll(Collection<D2bCandidateObservation> observations) {
        transaction.executeWithoutResult(status -> observations.forEach(this::save));
    }

    private void save(D2bCandidateObservation observation) {
        Map<String, String> list = publicFields(observation.listFields());
        Map<String, String> detail = publicFields(observation.detailFields());
        String revision = observation.revision() == null ? "" : observation.revision().trim();
        String identity = safeText(observation.sourceNoticeId());
        BidSourceCandidate previous = repository.findByIdentity("D2B", identity, revision).orElse(null);
        var relevance = classifier.classify(observation.title());
        boolean available = "AVAILABLE".equals(observation.detailStatus());
        D2bQualificationEvidence evidence = available
                ? D2bQualificationEvidence.rawDetail(detail.getOrDefault("lcnsLmttList", ""),
                    detail.getOrDefault("industClList", ""), "")
                : new D2bQualificationEvidence(
                    "ITEM_MISSING".equals(observation.detailStatus())
                        ? D2bQualificationEvidence.DetailStatus.ITEM_MISSING
                        : D2bQualificationEvidence.DetailStatus.FAILED,
                    null, null, null, D2bQualificationEvidence.ParseCompleteness.UNVERIFIED, List.of());
        // Extracted codes are diagnostic only: no verified OR/AND relationship is asserted.
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("completeness", "UNVERIFIED");
        parsed.put("lcnsLmttList", LicenseNormalizer.normalizeStructuredQualification(
                detail.getOrDefault("lcnsLmttList", "")));
        parsed.put("industClList", LicenseNormalizer.normalizeStructuredQualification(
                detail.getOrDefault("industClList", "")));
        var decision = evaluator.evaluate(evidence, relevance);
        String allowed = encode(new TreeSet<>(decision.allowedCodes()));
        // A transient failure must not erase the last successfully obtained public detail.
        String detailJson = !available && previous != null ? previous.detailFields() : encode(detail);
        String parsedJson = !available && previous != null ? previous.parsedQualification() : encode(parsed);
        repository.save(new BidSourceCandidate("D2B", identity, revision,
                union(previous == null ? null : previous.discoveryOperations(), observation.operations()),
                union(previous == null ? null : previous.discoveryPaths(), observation.paths()),
                safeText(observation.title()), encode(list), detailJson, parsedJson, observation.detailStatus(),
                decision.qualification().map(Enum::name).orElse(null), relevance.name(),
                decision.promotion().map(Enum::name).orElse(null),
                "PENDING".equals(observation.detailStatus()) ? "DETAIL_NOT_REQUESTED" : decision.reason().name(),
                allowed, hash(allowed), "d2b-public-fields-v1-unverified", "d2b-promotion-v1",
                previous == null ? clock.instant() : previous.firstSeenAt(), clock.instant()));
    }

    public static Map<String, String> publicFields(Map<String, String> fields) {
        Map<String, String> selected = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (PUBLIC_FIELDS.contains(key) && value != null) selected.put(key, safeText(value));
        });
        return selected;
    }

    public static String safeText(String value) {
        if (value == null) return "";
        // Do not persist URLs (including encoded URLs), credential assignments or auth headers.
        if (value.matches("(?is).*(https?://|https?%3a|servicekey|authorization|api[_-]?key|access[_-]?token|password|bearer\\s).*")) {
            return "[REDACTED]";
        }
        return value;
    }

    private String union(String previous, Set<String> current) {
        TreeSet<String> values = new TreeSet<>();
        if (previous != null) {
            try { values.addAll(json.readValue(previous, new TypeReference<List<String>>() { })); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored discovery metadata."); }
        }
        current.stream().map(D2bCandidateEvidenceService::safeText).forEach(values::add);
        return encode(values);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Candidate evidence serialization failed."); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
