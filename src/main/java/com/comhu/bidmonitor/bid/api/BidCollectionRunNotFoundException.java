package com.comhu.bidmonitor.bid.api;

public class BidCollectionRunNotFoundException extends RuntimeException {

    public BidCollectionRunNotFoundException(long runId) {
        super("Bid collection run was not found: " + runId);
    }
}
