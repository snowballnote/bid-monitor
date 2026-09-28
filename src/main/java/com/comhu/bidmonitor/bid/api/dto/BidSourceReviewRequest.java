package com.comhu.bidmonitor.bid.api.dto;

public record BidSourceReviewRequest(
        String registrationStatus,
        String collectionMethod,
        Boolean executionEnabled
) {

    public void rejectExecutionSetting() {
        if (executionEnabled != null) {
            throw new IllegalArgumentException("executionEnabled cannot be changed during review.");
        }
    }
}
