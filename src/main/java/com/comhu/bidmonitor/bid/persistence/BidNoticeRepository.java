package com.comhu.bidmonitor.bid.persistence;

import java.util.List;
import java.util.Optional;

public interface BidNoticeRepository {

    BidNoticeSaveResult save(BidNotice notice);

    Optional<BidNotice> findByIdentity(String sourceCode, String sourceNoticeId, String revision);

    Optional<BidNotice> findById(Long id);

    List<BidNotice> findAll();

    List<BidNoticeVersion> findVersions(Long bidNoticeId);
}
