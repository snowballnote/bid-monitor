package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDateTime;

/** Immutable snapshot retained when the current content changes within one revision. */
@Value
@Builder
public class BidNoticeVersion {

    Long id;
    Long bidNoticeId;
    String noticeNumber;
    String title;
    String orderingOrganization;
    LocalDateTime publishedAt;
    LocalDateTime submissionDeadlineAt;
    LocalDateTime bidOpeningAt;
    String contractMethod;
    String bidMethod;
    String noticeStatus;
    String noticeStatusCode;
    String detailUrl;
    boolean relevant;
    String analysisStatus;
    String analysisResult;
    String contentHash;
    Instant firstSeenAt;
    Instant lastSeenAt;
    Instant capturedAt;
}
