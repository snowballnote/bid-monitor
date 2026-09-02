package com.comhu.bidmonitor.notification.model;

import com.comhu.bidmonitor.externalnotice.change.NoticeChangeType;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 실제 전송기가 영속 엔티티에 의존하지 않고 사용할 외부공지 알림 후보이다. */
@Value
@Builder
public class ExternalNoticeNotificationCandidate {

    Long deliveryId;
    Long noticeId;
    String sourceCode;
    String externalId;
    String title;
    LocalDate publishedDate;
    NoticeChangeType changeType;

    @Singular
    List<String> matchedKeywords;

    String detailUrl;
    String contentFingerprint;
    Instant detectedAt;
}
