package com.comhu.bidmonitor.externalnotice.api.dto;

import com.comhu.bidmonitor.externalnotice.orchestration.ExternalNoticeCollectionResult;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 수동 수집 API가 반환하는 집계와 실패 목록이다. */
@Value
@Builder
public class ExternalNoticeCollectionResponse {

    int collectedCount;
    int newCount;
    int updatedCount;
    int unchangedCount;
    int piaRelatedCount;
    int failedCount;
    List<ExternalNoticeFailureResponse> failures;

    public static ExternalNoticeCollectionResponse from(ExternalNoticeCollectionResult result) {
        return ExternalNoticeCollectionResponse.builder()
                .collectedCount(result.getCollectedCount())
                .newCount(result.getNewCount())
                .updatedCount(result.getUpdatedCount())
                .unchangedCount(result.getUnchangedCount())
                .piaRelatedCount(result.getPiaRelatedCount())
                .failedCount(result.getFailedCount())
                .failures(result.getFailures().stream()
                        .map(ExternalNoticeFailureResponse::from)
                        .toList())
                .build();
    }
}
