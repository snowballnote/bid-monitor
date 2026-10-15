package com.comhu.bidmonitor.bid.source.d2b.promotion;

import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.bid.persistence.BidNoticeRepository;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidate;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit invocation only: no startup, scheduler or API registration. */
@Service
public class D2bLegacyCandidateBackfillService {
    private final JdbcTemplate jdbc;
    private final BidNoticeRepository notices;
    private final BidSourceCandidateRepository candidates;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public D2bLegacyCandidateBackfillService(JdbcTemplate jdbc, BidNoticeRepository notices,
            BidSourceCandidateRepository candidates, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.notices = notices;
        this.candidates = candidates;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    /**
     * Call again with lastNoticeId until scanned is zero. A failed batch returns no checkpoint:
     * retry the same afterNoticeId. Previously committed batches and existing evidence survive.
     */
    public BatchResult backfillBatch(long afterNoticeId, int batchSize) {
        if (afterNoticeId < 0 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Expected nonnegative checkpoint and batch size 1..1000.");
        }
        return transaction.execute(status -> {
            var ids = jdbc.queryForList("""
                    SELECT id FROM bid_notice WHERE source_code = ? AND id > ? ORDER BY id LIMIT ?
                    """, Long.class, "D2B", afterNoticeId, batchSize);
            int inserted = 0;
            for (Long id : ids) {
                BidNotice notice = notices.findById(id).orElseThrow();
                if (!"D2B".equals(notice.getSourceCode())) {
                    throw new IllegalStateException("Legacy source changed during backfill.");
                }
                if (candidates.findByIdentity("D2B", notice.getSourceNoticeId(), notice.getRevisionKey()).isPresent()) {
                    continue;
                }
                // Never use save/upsert here: concurrent fresh evidence must not be overwritten.
                candidates.insert(legacy(notice));
                inserted++;
            }
            return new BatchResult(ids.isEmpty() ? afterNoticeId : ids.getLast(), ids.size(), inserted,
                    ids.size() - inserted);
        });
    }

    private BidSourceCandidate legacy(BidNotice notice) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("legacyNoticeId", notice.getId());
        fields.put("noticeNumber", safe(notice.getNoticeNumber()));
        fields.put("orderingOrganization", safe(notice.getOrderingOrganization()));
        fields.put("publishedAt", notice.getPublishedAt() == null ? null : notice.getPublishedAt().toString());
        fields.put("legacyLastSeenAt", notice.getLastSeenAt().toString());
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("origin", "LEGACY");
        analysis.put("completeness", "UNVERIFIED");
        analysis.put("legacyRelevant", notice.isRelevant());
        analysis.put("legacyAnalysisStatus", safe(notice.getAnalysisStatus()));
        analysis.put("legacyAnalysisResult", safe(notice.getAnalysisResult()));
        analysis.put("allowedCodesKnown", false);
        // JSON null is unknown, not an invented empty/current allowed-code snapshot.
        // PENDING with null decisions distinguishes unavailable detail from normal ABSENT.
        return new BidSourceCandidate("D2B", notice.getSourceNoticeId(), notice.getRevisionKey(),
                "[]", "[\"LEGACY_BACKFILL\"]", safe(notice.getTitle()), encode(fields), "{}", encode(analysis),
                "PENDING", null, "UNKNOWN", null, "LEGACY_DETAIL_UNAVAILABLE", "null", "",
                "legacy-unverified-v1", "legacy-unassessed-v1", notice.getFirstSeenAt(),
                clock.instant().isBefore(notice.getFirstSeenAt()) ? notice.getFirstSeenAt() : clock.instant());
    }

    private String safe(String value) { return value == null ? null : D2bCandidateEvidenceService.safeText(value); }

    private String encode(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Legacy evidence serialization failed."); }
    }

    public record BatchResult(long lastNoticeId, int scanned, int inserted, int skipped) { }
}
