package com.comhu.bidmonitor.bid.persistence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDateTime;

/** Source-aware current snapshot of a collected bid notice. */
@Value
@Builder(toBuilder = true)
public class BidNotice {

    Long id;
    String sourceCode;
    String sourceNoticeId;
    String revisionKey;
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
}
