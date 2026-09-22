package com.comhu.bidmonitor.bid.persistence;

/** Outcome of one idempotent bid-notice persistence operation. */
public record BidNoticeSaveResult(ChangeType changeType, BidNotice notice, String previousContentHash) {

    public enum ChangeType {
        NEW,
        UPDATED,
        UNCHANGED
    }
}
