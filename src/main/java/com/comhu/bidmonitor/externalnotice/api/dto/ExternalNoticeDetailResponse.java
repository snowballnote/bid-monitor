package com.comhu.bidmonitor.externalnotice.api.dto;

import com.comhu.bidmonitor.externalnotice.persistence.ExternalNotice;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 저장 모델을 직접 노출하지 않고 공지 상세 확인에 필요한 값만 반환한다. */
@Value
@Builder
public class ExternalNoticeDetailResponse {

    Long id;
    String sourceCode;
    String externalId;
    String sourceNoticeId;
    String title;
    LocalDate publishedDate;
    String detailUrl;
    String body;
    boolean piaRelated;
    List<String> matchedKeywords;
    String classificationReason;
    String fingerprint;
    Instant firstSeenAt;
    Instant lastSeenAt;
    List<ExternalNoticeAttachmentResponse> attachments;

    public static ExternalNoticeDetailResponse from(ExternalNotice notice) {
        return ExternalNoticeDetailResponse.builder()
                .id(notice.getId())
                .sourceCode(notice.getSourceCode())
                .externalId(notice.getExternalId())
                .sourceNoticeId(notice.getSourceNoticeId())
                .title(notice.getTitle())
                .publishedDate(notice.getPublishedDate())
                .detailUrl(notice.getDetailUrl())
                .body(notice.getBody())
                .piaRelated(notice.isPiaRelated())
                .matchedKeywords(List.copyOf(notice.getMatchedKeywords()))
                .classificationReason(notice.getClassificationReason())
                .fingerprint(notice.getFingerprint())
                .firstSeenAt(notice.getFirstSeenAt())
                .lastSeenAt(notice.getLastSeenAt())
                .attachments(notice.getAttachments().stream()
                        .map(ExternalNoticeAttachmentResponse::from)
                        .toList())
                .build();
    }
}
