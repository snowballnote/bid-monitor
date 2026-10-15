package com.comhu.bidmonitor.bid.persistence;

import java.util.Optional;
import java.util.List;

public interface BidSourceCandidateRepository {
    /** Must be held until commit before reading settings or writing D2B evidence. */
    void lockD2bEvaluation();
    List<StoredCandidate> findD2bAfterId(long afterId, int size);
    void updateDecision(long id, BidSourceCandidate candidate);
    record StoredCandidate(long id, BidSourceCandidate candidate) { }
    /** Insert only; identity conflicts must never overwrite existing evidence. */
    void insert(BidSourceCandidate candidate);
    void save(BidSourceCandidate candidate);
    Optional<BidSourceCandidate> findByIdentity(String sourceCode, String sourceNoticeId, String revisionKey);
}
