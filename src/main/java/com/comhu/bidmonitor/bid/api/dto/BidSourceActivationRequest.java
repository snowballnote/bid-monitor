package com.comhu.bidmonitor.bid.api.dto;

public record BidSourceActivationRequest(Boolean executionEnabled) {

    public boolean requiredExecutionEnabled() {
        if (executionEnabled == null) {
            throw new IllegalArgumentException("executionEnabled is required.");
        }
        return executionEnabled;
    }
}
