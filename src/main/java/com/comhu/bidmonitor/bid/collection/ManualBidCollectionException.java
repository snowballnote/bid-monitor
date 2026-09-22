package com.comhu.bidmonitor.bid.collection;

public class ManualBidCollectionException extends IllegalStateException {

    private final ManualBidCollectionResult result;

    public ManualBidCollectionException(ManualBidCollectionResult result) {
        super("All enabled bid sources failed.");
        this.result = result;
    }

    public ManualBidCollectionResult getResult() {
        return result;
    }
}
