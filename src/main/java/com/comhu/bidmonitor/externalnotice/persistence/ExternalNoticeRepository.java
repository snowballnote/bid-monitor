package com.comhu.bidmonitor.externalnotice.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 저장 기술과 무관하게 외부공지를 저장하고 조회하기 위한 최소 Repository 계약이다. */
public interface ExternalNoticeRepository {

    ExternalNotice save(ExternalNotice notice);

    Optional<ExternalNotice> findByExternalId(String externalId);

    Optional<ExternalNotice> findById(Long id);

    List<ExternalNotice> findAll();

    List<ExternalNotice> findAllLatestFirst();

    List<ExternalNotice> findAllByPiaRelatedLatestFirst(boolean piaRelated);

    boolean existsByExternalId(String externalId);

    void updateLastSeenAt(Long noticeId, Instant lastSeenAt);

    ExternalNotice updateContent(Long noticeId, ExternalNotice notice);
}
