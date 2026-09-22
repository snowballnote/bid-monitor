package com.comhu.bidmonitor.bid.persistence.service;

import com.comhu.bidmonitor.dto.BidQualificationDto;

import java.util.List;

/** Collector output with an explicit source-level success or failure. */
public record BidSourceCollectionResult(
        String sourceCode,
        Status status,
        List<BidQualificationDto> candidates,
        String errorCode
) {

    public BidSourceCollectionResult {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode is required.");
        }
        sourceCode = sourceCode.trim();
        if (status == null) {
            throw new IllegalArgumentException("status is required.");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (status == Status.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException("A successful source result must not have an error code.");
        }
        if (status == Status.FAILED && !candidates.isEmpty()) {
            throw new IllegalArgumentException("A failed source result must not contain candidates.");
        }
    }

    public static BidSourceCollectionResult success(String sourceCode, List<BidQualificationDto> candidates) {
        return new BidSourceCollectionResult(sourceCode, Status.SUCCESS, candidates, null);
    }

    public static BidSourceCollectionResult failure(String sourceCode, String errorCode) {
        return new BidSourceCollectionResult(sourceCode, Status.FAILED, List.of(), errorCode);
    }

    public enum Status {
        SUCCESS,
        FAILED
    }
}
