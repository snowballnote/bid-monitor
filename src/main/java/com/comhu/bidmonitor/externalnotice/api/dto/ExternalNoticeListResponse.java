package com.comhu.bidmonitor.externalnotice.api.dto;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 목록에서 필요한 요약 필드만 노출해 본문과 fingerprint가 불필요하게 전송되지 않게 한다. */
@Value
@Builder
public class ExternalNoticeListResponse {

    Long id;
    String externalId;
    String title;
    LocalDate publishedDate;
    boolean piaRelated;
    List<String> matchedKeywords;
    Instant firstSeenAt;
    Instant lastSeenAt;
    String detailUrl;

    public static ExternalNoticeListResponse from(ExternalNotice notice) {
        return ExternalNoticeListResponse.builder()
                .id(notice.getId())
                .externalId(notice.getExternalId())
                .title(notice.getTitle())
                .publishedDate(notice.getPublishedDate())
                .piaRelated(notice.isPiaRelated())
                .matchedKeywords(List.copyOf(notice.getMatchedKeywords()))
                .firstSeenAt(notice.getFirstSeenAt())
                .lastSeenAt(notice.getLastSeenAt())
                .detailUrl(notice.getDetailUrl())
                .build();
    }
}
