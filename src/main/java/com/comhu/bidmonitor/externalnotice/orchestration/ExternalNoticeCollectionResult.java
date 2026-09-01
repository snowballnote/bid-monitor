package com.comhu.bidmonitor.externalnotice.orchestration;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeResult;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/** 한 번의 외부공지 수집 실행에서 처리된 상태별 건수와 개별 결과를 묶는다. */
@Value
@Builder
public class ExternalNoticeCollectionResult {

    int collectedCount;
    int newCount;
    int updatedCount;
    int unchangedCount;
    int piaRelatedCount;
    int failedCount;

    @Singular
    List<NoticeChangeResult> noticeResults;

    @Singular
    List<NoticeProcessingFailure> failures;
}
