package com.comhu.bidmonitor.externalnotice.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.LocalDate;
import java.util.List;

/**
 * 기관별 HTML 구조를 숨기고 이후 저장·분류 단계에 전달할 공통 수집 결과이다.
 * 아직 영속성 모델이 아니므로 최초 확인시각이나 변경 상태는 포함하지 않는다.
 */
@Value
@Builder
public class CollectedNotice {

    NoticeSource source;
    String externalId;
    String sourceNoticeId;
    String title;
    LocalDate publishedDate;
    String detailUrl;
    String body;

    @Singular
    List<NoticeAttachment> attachments;
}
