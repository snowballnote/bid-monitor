package com.comhu.bidmonitor.bid.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BidNoticeRepository {

    BidNoticeSaveResult save(BidNotice notice);

    Optional<BidNotice> findByIdentity(String sourceCode, String sourceNoticeId, String revision);

    Optional<BidNotice> findById(Long id);

    List<BidNotice> findAll();

    BidNoticePage findLatest(
            LocalDate startDate,
            LocalDate endDate,
            String sourceCode,
            int page,
            int size
    );

    List<BidNoticeVersion> findVersions(Long bidNoticeId);

    record BidNoticePage(List<BidNotice> items, long totalCount) {

        public BidNoticePage {
            items = List.copyOf(items);
        }
    }
}
