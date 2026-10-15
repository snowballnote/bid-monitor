package com.comhu.bidmonitor.bid.persistence;

import java.util.Optional;

public interface BidSourceCandidateRepository {
    /** Insert only; identity conflicts must never overwrite existing evidence. */
    void insert(BidSourceCandidate candidate);
    void save(BidSourceCandidate candidate);
    Optional<BidSourceCandidate> findByIdentity(String sourceCode, String sourceNoticeId, String revisionKey);
}
