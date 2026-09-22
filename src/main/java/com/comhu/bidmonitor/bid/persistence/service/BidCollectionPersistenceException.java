package com.comhu.bidmonitor.bid.persistence.service;

public class BidCollectionPersistenceException extends IllegalStateException {

    private final BidCollectionPersistenceResult result;

    public BidCollectionPersistenceException(BidCollectionPersistenceResult result) {
        super("All bid sources failed during persistence.");
        this.result = result;
    }

    public BidCollectionPersistenceResult getResult() {
        return result;
    }
}
