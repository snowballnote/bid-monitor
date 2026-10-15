package com.comhu.bidmonitor.bid.persistence.jdbc;

import com.comhu.bidmonitor.bid.persistence.BidSourceCandidate;
import com.comhu.bidmonitor.bid.persistence.BidSourceCandidateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.List;

@Repository
public class JdbcBidSourceCandidateRepository implements BidSourceCandidateRepository {
    private final JdbcTemplate jdbc;

    public JdbcBidSourceCandidateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockD2bEvaluation() {
        jdbc.queryForObject("SELECT id FROM bid_candidate_evaluation_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    @Override
    public List<StoredCandidate> findD2bAfterId(long afterId, int size) {
        return jdbc.query("SELECT * FROM bid_source_candidate WHERE source_code = 'D2B' AND id > ? ORDER BY id LIMIT ?",
                (r, row) -> new StoredCandidate(r.getLong("id"), map(r, row)), afterId, size);
    }

    @Override
    public void updateDecision(long id, BidSourceCandidate c) {
        jdbc.update("""
                UPDATE bid_source_candidate SET qualification_state=?, promotion_state=?, decision_reason=?,
                    allowed_codes=?, allowed_codes_hash=?, policy_version=?, updated_at=?
                WHERE id=? AND source_code='D2B'
                """, c.qualificationState(), c.promotionState(), c.decisionReason(), c.allowedCodes(),
                c.allowedCodesHash(), c.policyVersion(), Timestamp.from(c.updatedAt()), id);
    }

    @Override
    public void save(BidSourceCandidate c) {
        // Called in the evidence batch transaction. A concurrent first insert is protected by
        // the unique key; its failure rolls back the batch and leaves cursor/retry untouched.
        int updated = jdbc.update("""
                UPDATE bid_source_candidate SET
                    discovery_operations=?, discovery_paths=?, title=?, list_fields=?, detail_fields=?,
                    parsed_qualification=?, detail_status=?, qualification_state=?, title_relevance=?,
                    promotion_state=?, decision_reason=?, allowed_codes=?, allowed_codes_hash=?,
                    parser_version=?, policy_version=?, updated_at=?
                WHERE source_code=? AND source_notice_id=? AND revision_key=?
                """, c.discoveryOperations(), c.discoveryPaths(), c.title(), c.listFields(), c.detailFields(),
                c.parsedQualification(), c.detailStatus(), c.qualificationState(), c.titleRelevance(),
                c.promotionState(), c.decisionReason(), c.allowedCodes(), c.allowedCodesHash(),
                c.parserVersion(), c.policyVersion(), Timestamp.from(c.updatedAt()),
                c.sourceCode(), c.sourceNoticeId(), c.revisionKey());
        if (updated == 0) {
            insert(c);
        }
    }

    @Override
    public void insert(BidSourceCandidate c) {
            jdbc.update("""
                    INSERT INTO bid_source_candidate (
                        source_code, source_notice_id, revision_key, discovery_operations, discovery_paths,
                        title, list_fields, detail_fields, parsed_qualification, detail_status,
                        qualification_state, title_relevance, promotion_state, decision_reason,
                        allowed_codes, allowed_codes_hash, parser_version, policy_version, first_seen_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, c.sourceCode(), c.sourceNoticeId(), c.revisionKey(), c.discoveryOperations(),
                    c.discoveryPaths(), c.title(), c.listFields(), c.detailFields(), c.parsedQualification(),
                    c.detailStatus(), c.qualificationState(), c.titleRelevance(), c.promotionState(),
                    c.decisionReason(), c.allowedCodes(), c.allowedCodesHash(), c.parserVersion(), c.policyVersion(),
                    Timestamp.from(c.firstSeenAt()), Timestamp.from(c.updatedAt()));
    }

    @Override
    public Optional<BidSourceCandidate> findByIdentity(String source, String identity, String revision) {
        return jdbc.query("""
                SELECT * FROM bid_source_candidate
                WHERE source_code=? AND source_notice_id=? AND revision_key=?
                """, this::map, source, identity, revision == null ? "" : revision).stream().findFirst();
    }

    private BidSourceCandidate map(ResultSet r, int row) throws SQLException {
        return new BidSourceCandidate(r.getString("source_code"), r.getString("source_notice_id"),
                r.getString("revision_key"), r.getString("discovery_operations"), r.getString("discovery_paths"),
                r.getString("title"), r.getString("list_fields"), r.getString("detail_fields"),
                r.getString("parsed_qualification"), r.getString("detail_status"), r.getString("qualification_state"),
                r.getString("title_relevance"), r.getString("promotion_state"), r.getString("decision_reason"),
                r.getString("allowed_codes"), r.getString("allowed_codes_hash"), r.getString("parser_version"),
                r.getString("policy_version"), r.getTimestamp("first_seen_at").toInstant(),
                r.getTimestamp("updated_at").toInstant());
    }
}
