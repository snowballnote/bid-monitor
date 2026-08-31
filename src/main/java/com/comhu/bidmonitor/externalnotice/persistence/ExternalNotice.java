package com.comhu.bidmonitor.externalnotice.persistence;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 수집 결과, 분류 결과와 fingerprint를 함께 보관하는 외부공지 저장 모델이다. */
@Value
@Builder
public class ExternalNotice {

    Long id;
    String sourceCode;
    String externalId;
    String sourceNoticeId;
    String title;
    LocalDate publishedDate;
    String detailUrl;
    String body;
    boolean piaRelated;

    @Singular
    List<String> matchedKeywords;

    String classificationReason;
    String fingerprint;
    Instant firstSeenAt;
    Instant lastSeenAt;

    @Singular
    List<ExternalNoticeAttachment> attachments;
}
